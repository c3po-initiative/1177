package se.inera.journalen.proxy.ips;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.ServiceRequest;

import java.util.Collections;
import java.util.List;

/** Aggregated input to {@link PatientSummaryMapper}. */
public class PatientSummaryData {

    public final Patient patient;

    // Required IPS sections
    public final List<Condition> conditions;
    public final List<MedicationStatement> medications;
    public final List<AllergyIntolerance> allergies;

    // Recommended IPS sections
    public final List<Immunization> immunizations;
    public final List<Observation> labResults;

    // Optional IPS sections — surface what the proxy already maps.
    public final List<Observation> vitalSigns;
    public final List<Observation> functionalStatus;
    public final List<CarePlan> carePlans;
    public final List<ServiceRequest> serviceRequests;
    public final List<Encounter> encounters;
    public final List<DiagnosticReport> diagnosticReports;
    public final List<Consent> consents;

    /** True if the lab observation poll failed upstream (so we mark the Results section unavailable). */
    public final boolean labsUnavailable;
    /** True if the vital-signs poll failed (separate flag — both can fail independently). */
    public final boolean vitalSignsUnavailable;

    public PatientSummaryData(Patient patient,
                              List<Condition> conditions,
                              List<MedicationStatement> medications,
                              List<AllergyIntolerance> allergies,
                              List<Immunization> immunizations,
                              List<Observation> labResults,
                              boolean labsUnavailable) {
        // Backwards-compatible constructor for unit tests built against the original 5-section shape.
        this(patient, conditions, medications, allergies, immunizations, labResults,
             Collections.emptyList(), Collections.emptyList(),
             Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
             Collections.emptyList(), Collections.emptyList(),
             labsUnavailable, false);
    }

    public PatientSummaryData(Patient patient,
                              List<Condition> conditions,
                              List<MedicationStatement> medications,
                              List<AllergyIntolerance> allergies,
                              List<Immunization> immunizations,
                              List<Observation> labResults,
                              List<Observation> vitalSigns,
                              List<Observation> functionalStatus,
                              List<CarePlan> carePlans,
                              List<ServiceRequest> serviceRequests,
                              List<Encounter> encounters,
                              List<DiagnosticReport> diagnosticReports,
                              List<Consent> consents,
                              boolean labsUnavailable,
                              boolean vitalSignsUnavailable) {
        this.patient = patient;
        this.conditions = conditions;
        this.medications = medications;
        this.allergies = allergies;
        this.immunizations = immunizations;
        this.labResults = labResults;
        this.vitalSigns = vitalSigns;
        this.functionalStatus = functionalStatus;
        this.carePlans = carePlans;
        this.serviceRequests = serviceRequests;
        this.encounters = encounters;
        this.diagnosticReports = diagnosticReports;
        this.consents = consents;
        this.labsUnavailable = labsUnavailable;
        this.vitalSignsUnavailable = vitalSignsUnavailable;
    }
}
