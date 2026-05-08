package se.inera.journalen.proxy.ips;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import se.inera.journalen.proxy.mapping.ConditionMapper;
import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.providers.PatientResourceProvider;
import se.inera.journalen.proxy.upstream.FilterSpec;
import se.inera.journalen.proxy.upstream.InvanarClient;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;
import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.PollEnvelope;

/**
 * Aggregates the data needed for an IPS Patient summary by hitting all relevant journal-category
 * endpoints in parallel through one cached upstream session.
 *
 * Endpoints contacted (one fetch each):
 * <ul>
 *   <li>diagnosis → Conditions (Problems section, required)</li>
 *   <li>medication → MedicationStatements (Medications section, required)</li>
 *   <li>attentionSignals → AllergyIntolerances (Allergies section, required)</li>
 *   <li>vaccinationHistory → Immunizations (recommended)</li>
 *   <li>laboratoryoutcome/poll → Observations (Results section, recommended) — known to 500 on QA</li>
 *   <li>growthobservation/poll → Observations (Vital Signs section) — known to 500 on QA</li>
 *   <li>functionalStatus → Observations (Functional Status)</li>
 *   <li>careplan → CarePlans (Plan of Care, optional)</li>
 *   <li>referralStatus → ServiceRequests (Plan of Care, optional)</li>
 *   <li>journaloverview/polltimeline → Encounters (Past History, filtered to CareContact rows)</li>
 *   <li>laboratoryoutcome/pollanalysisoverview → DiagnosticReports (rolls into Results section)</li>
 *   <li>JournalBlock/Poll → Consents (Advance Directives section)</li>
 * </ul>
 *
 * Endpoints that 500 on the QA test users (lab, growth) are caught individually so the rest of
 * the bundle still builds. Their sections fall back to {@code emptyReason: unavailable}.
 */
public class PatientSummaryService {

    private static final Logger log = LoggerFactory.getLogger(PatientSummaryService.class);
    private static final String CARE_CONTACT_ATTR = "data-cy-journal-overview-item-type=\"CareContact\"";
    private static final int IPS_FETCH_LIMIT = 200;

    private final ExecutorService executor;

    public PatientSummaryService() {
        // 12 concurrent fetches max — one per category. Daemon threads.
        this.executor = Executors.newFixedThreadPool(12, r -> {
            Thread t = new Thread(r, "ips-fetch");
            t.setDaemon(true);
            return t;
        });
    }

    public PatientSummaryData fetch(InvanarClient client, String identifier, String patientReference) {
        PartialViewParser parser = new PartialViewParser();

        CompletableFuture<List<Condition>> fConditions = supply(() -> {
            PollEnvelope env = client.poll(InvanarEndpoints.DIAGNOSIS_POLL, FilterSpec.of(0, IPS_FETCH_LIMIT));
            List<Condition> out = new ArrayList<>();
            for (var row : parser.parseListRows(env.htmlBody())) {
                out.add(ConditionMapper.fromListRow(row, patientReference));
            }
            return out;
        }, "diagnosis");

        CompletableFuture<List<MedicationStatement>> fMeds = mapPoll(client, parser,
                InvanarEndpoints.MEDICATION_POLL, FilterSpec.of(0, IPS_FETCH_LIMIT),
                row -> SkeletonMappers.medicationStatement(row, patientReference), "medication");

        CompletableFuture<List<AllergyIntolerance>> fAllergies = mapPoll(client, parser,
                InvanarEndpoints.ATTENTION_SIGNALS_POLL, FilterSpec.of(0, IPS_FETCH_LIMIT),
                row -> SkeletonMappers.allergyIntolerance(row, patientReference), "attentionSignals");

        CompletableFuture<List<Immunization>> fImms = mapPoll(client, parser,
                InvanarEndpoints.VACCINATION_POLL, FilterSpec.of(0, IPS_FETCH_LIMIT),
                row -> SkeletonMappers.immunization(row, patientReference), "vaccinationHistory");

        boolean[] labsUnavailable = {false};
        CompletableFuture<List<Observation>> fObs = supplyOrEmpty(() -> {
            PollEnvelope env = client.poll(InvanarEndpoints.LAB_POLL, FilterSpec.of(0, IPS_FETCH_LIMIT));
            List<Observation> out = new ArrayList<>();
            for (var row : parser.parseListRows(env.htmlBody())) {
                out.add(SkeletonMappers.laboratory(row, patientReference));
            }
            return out;
        }, labsUnavailable, "laboratoryoutcome");

        boolean[] vitalsUnavailable = {false};
        CompletableFuture<List<Observation>> fVitals = supplyOrEmpty(() -> {
            // Growth/vital-signs uses the skip-only filter shape (no Take, no GetFiltersView)
            PollEnvelope env = client.poll(InvanarEndpoints.GROWTH_POLL, FilterSpec.ofSkipOnly(0));
            List<Observation> out = new ArrayList<>();
            for (var row : parser.parseListRows(env.htmlBody())) {
                out.add(SkeletonMappers.growth(row, patientReference));
            }
            return out;
        }, vitalsUnavailable, "growthobservation");

        CompletableFuture<List<Observation>> fFuncStatus = mapPoll(client, parser,
                InvanarEndpoints.FUNCTIONAL_STATUS_POLL, FilterSpec.of(0, IPS_FETCH_LIMIT),
                row -> SkeletonMappers.functionalStatus(row, patientReference), "functionalStatus");

        CompletableFuture<List<CarePlan>> fCarePlans = mapPoll(client, parser,
                InvanarEndpoints.CAREPLAN_POLL, FilterSpec.of(0, IPS_FETCH_LIMIT),
                row -> SkeletonMappers.carePlan(row, patientReference), "careplan");

        CompletableFuture<List<ServiceRequest>> fServiceRequests = mapPoll(client, parser,
                InvanarEndpoints.REFERRAL_POLL, FilterSpec.of(0, IPS_FETCH_LIMIT),
                row -> SkeletonMappers.serviceRequest(row, patientReference), "referralStatus");

        // Encounters: filter the journal-overview timeline to CareContact rows only.
        CompletableFuture<List<Encounter>> fEncounters = supply(() -> {
            PollEnvelope env = client.poll(InvanarEndpoints.JOURNAL_TIMELINE, FilterSpec.of(0, IPS_FETCH_LIMIT));
            List<Encounter> out = new ArrayList<>();
            for (var row : parser.parseListRows(env.htmlBody())) {
                if (row.html != null && row.html.contains(CARE_CONTACT_ATTR)) {
                    out.add(SkeletonMappers.encounter(row, patientReference));
                }
            }
            return out;
        }, "journaloverview");

        boolean[] reportsUnavailable = {false};
        CompletableFuture<List<DiagnosticReport>> fReports = supplyOrEmpty(() -> {
            PollEnvelope env = client.poll(InvanarEndpoints.LAB_OVERVIEW, FilterSpec.ofSkipOnly(0));
            List<DiagnosticReport> out = new ArrayList<>();
            for (var row : parser.parseListRows(env.htmlBody())) {
                out.add(SkeletonMappers.diagnosticReport(row, patientReference));
            }
            return out;
        }, reportsUnavailable, "lab-overview");

        // Consents: JournalBlock/Poll has its own custom OrderByEnum — build the body inline.
        CompletableFuture<List<Consent>> fConsents = supplyOrEmpty(() -> {
            Map<String, Object> fs = new LinkedHashMap<>();
            fs.put("Skip", 0);
            fs.put("Take", IPS_FETCH_LIMIT);
            for (String f : new String[]{"AuthorName","Type","InformationType","CareUnit","VaccineName",
                    "VaccineDisease","MedicationName","OngoingTreatment","LoggedPersonName",
                    "LoggedPersonRole","LoggedPersonCareProvider"}) {
                fs.put(f, Collections.emptyList());
            }
            fs.put("OrderDirection", "Ascending");
            fs.put("OrderByEnum", "BlockCareUnitAndCareProvider");
            fs.put("FilterArrays", Collections.emptyMap());
            fs.put("GetFiltersView", false);
            PollEnvelope env = client.postJson(InvanarEndpoints.JOURNAL_BLOCKS, Map.of("fs", fs));
            List<Consent> out = new ArrayList<>();
            for (var row : parser.parseListRows(env.htmlBody())) {
                out.add(SkeletonMappers.consent(row, patientReference));
            }
            return out;
        }, new boolean[1], "journalBlock");

        CompletableFuture.allOf(fConditions, fMeds, fAllergies, fImms, fObs, fVitals,
                fFuncStatus, fCarePlans, fServiceRequests, fEncounters, fReports, fConsents).join();

        Patient patient = synthesizePatient(identifier);
        return new PatientSummaryData(
                patient,
                join(fConditions),
                join(fMeds),
                join(fAllergies),
                join(fImms),
                join(fObs),
                join(fVitals),
                join(fFuncStatus),
                join(fCarePlans),
                join(fServiceRequests),
                join(fEncounters),
                join(fReports),
                join(fConsents),
                labsUnavailable[0],
                vitalsUnavailable[0]);
    }

    public void close() {
        executor.shutdown();
    }

    static Patient synthesizePatient(String identifier) {
        Patient p = new Patient();
        p.setId(java.util.UUID.randomUUID().toString());
        if (identifier != null) {
            p.addIdentifier(new Identifier()
                    .setSystem(PatientResourceProvider.PNR_SYSTEM)
                    .setValue(identifier));
        }
        p.getMeta().addProfile(IpsProfiles.PATIENT);
        return p;
    }

    /** Standard pollAndMap shape for a category that uses {@link FilterSpec#of(int,int)}. */
    private <T> CompletableFuture<List<T>> mapPoll(InvanarClient client, PartialViewParser parser,
                                                   String path, FilterSpec fs,
                                                   java.util.function.Function<se.inera.journalen.proxy.upstream.dto.ListRow, T> mapper,
                                                   String label) {
        return supply(() -> {
            PollEnvelope env = client.poll(path, fs);
            List<T> out = new ArrayList<>();
            for (var row : parser.parseListRows(env.htmlBody())) {
                T r = mapper.apply(row);
                if (r != null) out.add(r);
            }
            return out;
        }, label);
    }

    private <T> CompletableFuture<T> supply(IoSupplier<T> s, String label) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return s.get();
            } catch (IOException e) {
                throw new RuntimeException("IPS fetch failed for " + label + ": " + e.getMessage(), e);
            }
        }, executor);
    }

    /** Like {@link #supply} but catches {@link InvanarClient.UpstreamException} and sets the unavailable flag. */
    private <T> CompletableFuture<List<T>> supplyOrEmpty(IoSupplier<List<T>> s, boolean[] unavailableFlag, String label) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return s.get();
            } catch (InvanarClient.UpstreamException e) {
                log.info("IPS section '{}' unavailable (HTTP {}): {}", label, e.statusCode, e.getMessage());
                unavailableFlag[0] = true;
                return Collections.emptyList();
            } catch (IOException e) {
                throw new RuntimeException("IPS fetch failed for " + label + ": " + e.getMessage(), e);
            }
        }, executor);
    }

    private static <T> T join(CompletableFuture<T> f) {
        try {
            return f.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> { T get() throws IOException; }
}
