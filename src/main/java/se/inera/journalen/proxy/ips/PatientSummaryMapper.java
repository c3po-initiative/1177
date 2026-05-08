package se.inera.journalen.proxy.ips;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Builds an IPS document Bundle. Mirrors the dhroxy pattern: Composition is the first
 * entry; each clinical resource follows with a {@code urn:uuid:&lt;id&gt;} fullUrl;
 * Composition.section[].entry references those urn:uuid URLs.
 */
public final class PatientSummaryMapper {

    private static final String AUTHOR_DISPLAY = "1177 Journalen via journalen-fhir-proxy";

    private PatientSummaryMapper() {}

    public static Bundle toIpsBundle(PatientSummaryData data) {
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.DOCUMENT);
        bundle.setTimestamp(new Date());
        bundle.setIdentifier(new Identifier()
                .setSystem("urn:ietf:rfc:3986")
                .setValue("urn:uuid:" + UUID.randomUUID()));

        // Stable urn:uuid for the patient — referenced by every clinical resource and the Composition.
        ensureId(data.patient);
        applyProfile(data.patient, IpsProfiles.PATIENT);
        Reference patientRef = new Reference("urn:uuid:" + data.patient.getIdElement().getIdPart());

        // Tag every resource with its IPS profile + ensure it has an id we can reference.
        for (Condition c : data.conditions)            { ensureId(c); applyProfile(c, IpsProfiles.CONDITION); c.setSubject(patientRef); }
        for (MedicationStatement m : data.medications) { ensureId(m); applyProfile(m, IpsProfiles.MEDICATION); m.setSubject(patientRef); }
        for (AllergyIntolerance a : data.allergies)    { ensureId(a); applyProfile(a, IpsProfiles.ALLERGY); a.setPatient(patientRef); }
        for (Immunization i : data.immunizations)      { ensureId(i); applyProfile(i, IpsProfiles.IMMUNIZATION); i.setPatient(patientRef); }
        for (Observation o : data.labResults)          { ensureId(o); applyProfile(o, IpsProfiles.OBSERVATION_LAB); o.setSubject(patientRef); }
        for (Observation o : data.vitalSigns)          { ensureId(o); applyProfile(o, IpsProfiles.OBSERVATION_VITAL_SIGNS); o.setSubject(patientRef); }
        for (Observation o : data.functionalStatus)    { ensureId(o); o.setSubject(patientRef); }
        for (CarePlan cp : data.carePlans)             { ensureId(cp); applyProfile(cp, IpsProfiles.CARE_PLAN); cp.setSubject(patientRef); }
        for (ServiceRequest sr : data.serviceRequests) { ensureId(sr); applyProfile(sr, IpsProfiles.SERVICE_REQUEST); sr.setSubject(patientRef); }
        for (Encounter e : data.encounters)            { ensureId(e); applyProfile(e, IpsProfiles.ENCOUNTER); e.setSubject(patientRef); }
        for (DiagnosticReport r : data.diagnosticReports) { ensureId(r); applyProfile(r, IpsProfiles.DIAGNOSTIC_REPORT); r.setSubject(patientRef); }
        for (Consent c : data.consents)                { ensureId(c); applyProfile(c, IpsProfiles.CONSENT); c.setPatient(patientRef); }

        Composition composition = buildComposition(data, patientRef);
        ensureId(composition);

        // Composition is always the first entry of an IPS Document Bundle.
        bundle.addEntry(entry(composition));
        bundle.addEntry(entry(data.patient));
        addAll(bundle, data.conditions);
        addAll(bundle, data.medications);
        addAll(bundle, data.allergies);
        addAll(bundle, data.immunizations);
        addAll(bundle, data.labResults);
        addAll(bundle, data.vitalSigns);
        addAll(bundle, data.functionalStatus);
        addAll(bundle, data.carePlans);
        addAll(bundle, data.serviceRequests);
        addAll(bundle, data.encounters);
        addAll(bundle, data.diagnosticReports);
        addAll(bundle, data.consents);
        return bundle;
    }

    private static <R extends DomainResource> void addAll(Bundle b, List<R> resources) {
        for (R r : resources) b.addEntry(entry(r));
    }

    private static Composition buildComposition(PatientSummaryData data, Reference patientRef) {
        Composition c = new Composition();
        // Plain UUID — RFC-4122 compliant for the bundle's urn:uuid fullUrl.
        c.setId(UUID.randomUUID().toString());
        c.getMeta().addProfile(IpsProfiles.COMPOSITION);
        c.setStatus(Composition.CompositionStatus.FINAL);
        c.setType(new CodeableConcept().addCoding(new Coding()
                .setSystem(IpsProfiles.LOINC)
                .setCode(IpsProfiles.IPS_DOC_TYPE_CODE)
                .setDisplay("Patient summary Document")));
        c.setSubject(patientRef);
        c.setDate(new Date());
        c.addAuthor(new Reference().setDisplay(AUTHOR_DISPLAY));
        c.setTitle("International Patient Summary");

        // Required + recommended sections — always present, with no-known/emptyReason fallbacks.
        c.addSection(problemsSection(data.conditions, patientRef));
        c.addSection(medicationsSection(data.medications, patientRef));
        c.addSection(allergiesSection(data.allergies, patientRef));
        c.addSection(immunizationsSection(data.immunizations, patientRef));
        c.addSection(resultsSection(data.labResults, data.diagnosticReports, data.labsUnavailable));

        // Optional sections — only emit when there is data (or, for Vital Signs, when the
        // upstream is unavailable so we can flag it explicitly). Keeps minimal-data IPS bundles
        // small but surfaces all the proxy's coverage when the patient has it.
        if (!data.vitalSigns.isEmpty() || data.vitalSignsUnavailable) {
            c.addSection(vitalSignsSection(data.vitalSigns, data.vitalSignsUnavailable));
        }
        if (!data.functionalStatus.isEmpty()) {
            c.addSection(functionalStatusSection(data.functionalStatus));
        }
        if (!data.carePlans.isEmpty() || !data.serviceRequests.isEmpty()) {
            c.addSection(planOfCareSection(data.carePlans, data.serviceRequests));
        }
        if (!data.encounters.isEmpty()) {
            c.addSection(pastHistorySection(data.encounters));
        }
        if (!data.consents.isEmpty()) {
            c.addSection(advanceDirectivesSection(data.consents));
        }
        return c;
    }

    // ---------------------- sections ----------------------

    private static Composition.SectionComponent problemsSection(List<Condition> conditions, Reference patientRef) {
        Composition.SectionComponent s = new Composition.SectionComponent()
                .setTitle("Problem List")
                .setCode(loinc(IpsProfiles.SECTION_PROBLEMS, "Problem list - Reported"));
        if (conditions.isEmpty()) {
            s.addEntry(noKnown(IpsProfiles.IPS_ABSENT_UNKNOWN, "no-known-problems", "No known problems"));
        } else {
            for (Condition c : conditions) s.addEntry(uuidRef(c));
        }
        return s;
    }

    private static Composition.SectionComponent medicationsSection(List<MedicationStatement> meds, Reference patientRef) {
        Composition.SectionComponent s = new Composition.SectionComponent()
                .setTitle("Medication Summary")
                .setCode(loinc(IpsProfiles.SECTION_MEDS, "History of Medication use Narrative"));
        if (meds.isEmpty()) {
            s.addEntry(noKnown(IpsProfiles.IPS_ABSENT_UNKNOWN, "no-known-medications", "No known medications"));
        } else {
            for (MedicationStatement m : meds) s.addEntry(uuidRef(m));
        }
        return s;
    }

    private static Composition.SectionComponent allergiesSection(List<AllergyIntolerance> allergies, Reference patientRef) {
        Composition.SectionComponent s = new Composition.SectionComponent()
                .setTitle("Allergies and Intolerances")
                .setCode(loinc(IpsProfiles.SECTION_ALLERGIES, "Allergies and adverse reactions Document"));
        if (allergies.isEmpty()) {
            s.addEntry(noKnown(IpsProfiles.IPS_ABSENT_UNKNOWN, "no-known-allergies", "No known allergies"));
        } else {
            for (AllergyIntolerance a : allergies) s.addEntry(uuidRef(a));
        }
        return s;
    }

    private static Composition.SectionComponent immunizationsSection(List<Immunization> imms, Reference patientRef) {
        Composition.SectionComponent s = new Composition.SectionComponent()
                .setTitle("Immunizations")
                .setCode(loinc(IpsProfiles.SECTION_IMMS, "History of Immunization Narrative"));
        if (imms.isEmpty()) {
            s.setEmptyReason(new CodeableConcept().addCoding(new Coding()
                    .setSystem(IpsProfiles.LIST_EMPTY_REASON)
                    .setCode("nilknown").setDisplay("Nil Known")));
        } else {
            for (Immunization i : imms) s.addEntry(uuidRef(i));
        }
        return s;
    }

    private static Composition.SectionComponent resultsSection(List<Observation> obs,
                                                                List<DiagnosticReport> reports,
                                                                boolean unavailable) {
        Composition.SectionComponent s = new Composition.SectionComponent()
                .setTitle("Results")
                .setCode(loinc(IpsProfiles.SECTION_RESULTS, "Relevant diagnostic tests/laboratory data Narrative"));
        if (unavailable) {
            s.setEmptyReason(new CodeableConcept().addCoding(new Coding()
                    .setSystem(IpsProfiles.LIST_EMPTY_REASON)
                    .setCode("unavailable").setDisplay("Unavailable")));
            return s;
        }
        if (obs.isEmpty() && reports.isEmpty()) {
            s.setEmptyReason(new CodeableConcept().addCoding(new Coding()
                    .setSystem(IpsProfiles.LIST_EMPTY_REASON)
                    .setCode("nilknown").setDisplay("Nil Known")));
        } else {
            for (Observation o : obs) s.addEntry(uuidRef(o));
            for (DiagnosticReport r : reports) s.addEntry(uuidRef(r));
        }
        return s;
    }

    private static Composition.SectionComponent vitalSignsSection(List<Observation> obs, boolean unavailable) {
        Composition.SectionComponent s = new Composition.SectionComponent()
                .setTitle("Vital Signs")
                .setCode(loinc(IpsProfiles.SECTION_VITAL_SIGNS, "Vital signs"));
        if (unavailable) {
            s.setEmptyReason(new CodeableConcept().addCoding(new Coding()
                    .setSystem(IpsProfiles.LIST_EMPTY_REASON)
                    .setCode("unavailable").setDisplay("Unavailable")));
        } else {
            for (Observation o : obs) s.addEntry(uuidRef(o));
        }
        return s;
    }

    private static Composition.SectionComponent functionalStatusSection(List<Observation> obs) {
        Composition.SectionComponent s = new Composition.SectionComponent()
                .setTitle("Functional Status")
                .setCode(loinc(IpsProfiles.SECTION_FUNCTIONAL_STATUS, "Functional status assessment note"));
        for (Observation o : obs) s.addEntry(uuidRef(o));
        return s;
    }

    private static Composition.SectionComponent planOfCareSection(List<CarePlan> plans, List<ServiceRequest> requests) {
        Composition.SectionComponent s = new Composition.SectionComponent()
                .setTitle("Plan of Care")
                .setCode(loinc(IpsProfiles.SECTION_PLAN_OF_CARE, "Plan of treatment"));
        for (CarePlan p : plans) s.addEntry(uuidRef(p));
        for (ServiceRequest r : requests) s.addEntry(uuidRef(r));
        return s;
    }

    private static Composition.SectionComponent pastHistorySection(List<Encounter> encounters) {
        Composition.SectionComponent s = new Composition.SectionComponent()
                .setTitle("Past History of Encounters")
                .setCode(loinc(IpsProfiles.SECTION_PAST_HISTORY, "History of Past illness Narrative"));
        for (Encounter e : encounters) s.addEntry(uuidRef(e));
        return s;
    }

    private static Composition.SectionComponent advanceDirectivesSection(List<Consent> consents) {
        Composition.SectionComponent s = new Composition.SectionComponent()
                .setTitle("Advance Directives")
                .setCode(loinc(IpsProfiles.SECTION_ADVANCE_DIRECTIVES, "Advance directives"));
        for (Consent c : consents) s.addEntry(uuidRef(c));
        return s;
    }

    // ---------------------- helpers ----------------------

    private static CodeableConcept loinc(String code, String display) {
        return new CodeableConcept().addCoding(new Coding()
                .setSystem(IpsProfiles.LOINC).setCode(code).setDisplay(display));
    }

    /** Composition.section.entry that points at one of the bundle entries via {@code urn:uuid:}. */
    private static Reference uuidRef(Resource r) {
        return new Reference("urn:uuid:" + r.getIdElement().getIdPart());
    }

    /** A "no-known" Reference using the IPS absent-unknown code system. Inline coding via display only. */
    private static Reference noKnown(String system, String code, String display) {
        // Section.entry is a Reference, not a CodeableConcept, so we use display + identifier.
        return new Reference()
                .setDisplay(display)
                .setIdentifier(new Identifier().setSystem(system).setValue(code));
    }

    private static void ensureId(Resource r) {
        if (r.getIdElement() == null || r.getIdElement().isEmpty()) {
            r.setId(UUID.randomUUID().toString());
        }
    }

    private static void applyProfile(Resource r, String profileUrl) {
        if (r.getMeta() == null || !r.getMeta().getProfile().stream()
                .anyMatch(c -> profileUrl.equals(c.getValue()))) {
            r.getMeta().addProfile(profileUrl);
        }
    }

    private static Bundle.BundleEntryComponent entry(Resource r) {
        return new Bundle.BundleEntryComponent()
                .setFullUrl("urn:uuid:" + r.getIdElement().getIdPart())
                .setResource(r);
    }

    /** Visible to tests for assertions. */
    public static List<Resource> bundleResources(Bundle b) {
        List<Resource> out = new ArrayList<>();
        for (var e : b.getEntry()) out.add(e.getResource());
        return out;
    }
}
