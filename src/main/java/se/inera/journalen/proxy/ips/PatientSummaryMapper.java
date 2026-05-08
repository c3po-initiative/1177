package se.inera.journalen.proxy.ips;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;

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
        for (Observation o : data.observations)        { ensureId(o); applyProfile(o, IpsProfiles.OBSERVATION_LAB); o.setSubject(patientRef); }

        Composition composition = buildComposition(data, patientRef);
        ensureId(composition);

        // Composition is always the first entry of an IPS Document Bundle.
        bundle.addEntry(entry(composition));
        bundle.addEntry(entry(data.patient));
        for (Condition c : data.conditions) bundle.addEntry(entry(c));
        for (MedicationStatement m : data.medications) bundle.addEntry(entry(m));
        for (AllergyIntolerance a : data.allergies) bundle.addEntry(entry(a));
        for (Immunization i : data.immunizations) bundle.addEntry(entry(i));
        for (Observation o : data.observations) bundle.addEntry(entry(o));
        return bundle;
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

        c.addSection(problemsSection(data.conditions, patientRef));
        c.addSection(medicationsSection(data.medications, patientRef));
        c.addSection(allergiesSection(data.allergies, patientRef));
        c.addSection(immunizationsSection(data.immunizations, patientRef));
        c.addSection(resultsSection(data.observations, data.labsUnavailable));
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

    private static Composition.SectionComponent resultsSection(List<Observation> obs, boolean unavailable) {
        Composition.SectionComponent s = new Composition.SectionComponent()
                .setTitle("Results")
                .setCode(loinc(IpsProfiles.SECTION_RESULTS, "Relevant diagnostic tests/laboratory data Narrative"));
        if (unavailable) {
            s.setEmptyReason(new CodeableConcept().addCoding(new Coding()
                    .setSystem(IpsProfiles.LIST_EMPTY_REASON)
                    .setCode("unavailable").setDisplay("Unavailable")));
            return s;
        }
        if (obs.isEmpty()) {
            s.setEmptyReason(new CodeableConcept().addCoding(new Coding()
                    .setSystem(IpsProfiles.LIST_EMPTY_REASON)
                    .setCode("nilknown").setDisplay("Nil Known")));
        } else {
            for (Observation o : obs) s.addEntry(uuidRef(o));
        }
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
