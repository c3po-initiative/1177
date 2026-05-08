package se.inera.journalen.proxy.ips;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;

import java.util.List;

/** Aggregated input to {@link PatientSummaryMapper}. */
public class PatientSummaryData {
    public final Patient patient;
    public final List<Condition> conditions;
    public final List<MedicationStatement> medications;
    public final List<AllergyIntolerance> allergies;
    public final List<Immunization> immunizations;
    public final List<Observation> observations;

    /** True if the lab observation poll failed upstream (so we mark the Results section unavailable). */
    public final boolean labsUnavailable;

    public PatientSummaryData(Patient patient,
                              List<Condition> conditions,
                              List<MedicationStatement> medications,
                              List<AllergyIntolerance> allergies,
                              List<Immunization> immunizations,
                              List<Observation> observations,
                              boolean labsUnavailable) {
        this.patient = patient;
        this.conditions = conditions;
        this.medications = medications;
        this.allergies = allergies;
        this.immunizations = immunizations;
        this.observations = observations;
        this.labsUnavailable = labsUnavailable;
    }
}
