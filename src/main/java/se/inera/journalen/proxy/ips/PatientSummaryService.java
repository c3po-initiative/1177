package se.inera.journalen.proxy.ips;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * Aggregates the data needed for an IPS Patient summary by hitting all the relevant
 * journal-category poll endpoints in parallel through a single cached upstream session.
 *
 * The cached {@code InvanarClient} uses Apache HttpClient 5 with a connection pool, so
 * concurrent requests share the cookie jar safely and complete in roughly the slowest
 * single call's time rather than the sum.
 */
public class PatientSummaryService {

    private static final Logger log = LoggerFactory.getLogger(PatientSummaryService.class);

    /** Filter window for what we send upstream. IPS doesn't dictate a max; we cap to keep memory bounded. */
    private static final int IPS_FETCH_LIMIT = 200;

    private final ExecutorService executor;

    public PatientSummaryService() {
        // Six concurrent fetches max — one per category. Daemon threads so we don't block JVM shutdown.
        this.executor = Executors.newFixedThreadPool(6, r -> {
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

        CompletableFuture<List<MedicationStatement>> fMeds = supply(() -> {
            PollEnvelope env = client.poll(InvanarEndpoints.MEDICATION_POLL, FilterSpec.of(0, IPS_FETCH_LIMIT));
            List<MedicationStatement> out = new ArrayList<>();
            for (var row : parser.parseListRows(env.htmlBody())) {
                out.add(SkeletonMappers.medicationStatement(row, patientReference));
            }
            return out;
        }, "medication");

        CompletableFuture<List<AllergyIntolerance>> fAllergies = supply(() -> {
            PollEnvelope env = client.poll(InvanarEndpoints.ATTENTION_SIGNALS_POLL, FilterSpec.of(0, IPS_FETCH_LIMIT));
            List<AllergyIntolerance> out = new ArrayList<>();
            for (var row : parser.parseListRows(env.htmlBody())) {
                out.add(SkeletonMappers.allergyIntolerance(row, patientReference));
            }
            return out;
        }, "attentionSignals");

        CompletableFuture<List<Immunization>> fImms = supply(() -> {
            PollEnvelope env = client.poll(InvanarEndpoints.VACCINATION_POLL, FilterSpec.of(0, IPS_FETCH_LIMIT));
            List<Immunization> out = new ArrayList<>();
            for (var row : parser.parseListRows(env.htmlBody())) {
                out.add(SkeletonMappers.immunization(row, patientReference));
            }
            return out;
        }, "vaccinationHistory");

        // Lab is known to 500 on the QA portal for these test users. Catch and mark unavailable
        // so the IPS Bundle reports it correctly via Composition.section.emptyReason.
        boolean[] labsUnavailableHolder = {false};
        CompletableFuture<List<Observation>> fObs = supply(() -> {
            try {
                PollEnvelope env = client.poll(InvanarEndpoints.LAB_POLL, FilterSpec.of(0, IPS_FETCH_LIMIT));
                List<Observation> out = new ArrayList<>();
                for (var row : parser.parseListRows(env.htmlBody())) {
                    out.add(SkeletonMappers.laboratory(row, patientReference));
                }
                return out;
            } catch (InvanarClient.UpstreamException e) {
                log.info("Lab unavailable for IPS (HTTP {}): {}", e.statusCode, e.getMessage());
                labsUnavailableHolder[0] = true;
                return Collections.<Observation>emptyList();
            }
        }, "laboratoryoutcome");

        // Wait for all to finish; bubble any unhandled IOException as a runtime error.
        CompletableFuture.allOf(fConditions, fMeds, fAllergies, fImms, fObs).join();

        Patient patient = synthesizePatient(identifier);
        return new PatientSummaryData(
                patient,
                join(fConditions),
                join(fMeds),
                join(fAllergies),
                join(fImms),
                join(fObs),
                labsUnavailableHolder[0]);
    }

    public void close() {
        executor.shutdown();
    }

    static Patient synthesizePatient(String identifier) {
        Patient p = new Patient();
        // Use a fresh UUID so the bundle entry's urn:uuid fullUrl is RFC-4122 compliant.
        // The Swedish personnummer is preserved in Patient.identifier.
        p.setId(java.util.UUID.randomUUID().toString());
        if (identifier != null) {
            p.addIdentifier(new Identifier()
                    .setSystem(PatientResourceProvider.PNR_SYSTEM)
                    .setValue(identifier));
        }
        p.getMeta().addProfile(IpsProfiles.PATIENT);
        return p;
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
