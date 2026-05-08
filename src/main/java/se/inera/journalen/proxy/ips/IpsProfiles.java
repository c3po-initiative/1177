package se.inera.journalen.proxy.ips;

/**
 * Constants for the International Patient Summary (uv-ips) implementation guide.
 * Profile URLs and LOINC section codes copied from the IPS spec
 * (http://hl7.org/fhir/uv/ips/). Same set as dhroxy.
 */
public final class IpsProfiles {
    private IpsProfiles() {}

    public static final String COMPOSITION = "http://hl7.org/fhir/uv/ips/StructureDefinition/Composition-uv-ips";
    public static final String PATIENT     = "http://hl7.org/fhir/uv/ips/StructureDefinition/Patient-uv-ips";
    public static final String CONDITION   = "http://hl7.org/fhir/uv/ips/StructureDefinition/Condition-uv-ips";
    public static final String ALLERGY     = "http://hl7.org/fhir/uv/ips/StructureDefinition/AllergyIntolerance-uv-ips";
    public static final String MEDICATION  = "http://hl7.org/fhir/uv/ips/StructureDefinition/MedicationStatement-uv-ips";
    public static final String IMMUNIZATION= "http://hl7.org/fhir/uv/ips/StructureDefinition/Immunization-uv-ips";
    public static final String OBSERVATION_LAB = "http://hl7.org/fhir/uv/ips/StructureDefinition/Observation-results-laboratory-uv-ips";

    public static final String LOINC = "http://loinc.org";
    public static final String IPS_DOC_TYPE_CODE = "60591-5"; // Patient summary Document
    public static final String SECTION_PROBLEMS = "11450-4";  // Problem list - Reported
    public static final String SECTION_MEDS     = "10160-0";  // History of Medication use Narrative
    public static final String SECTION_ALLERGIES= "48765-2";  // Allergies and adverse reactions Document
    public static final String SECTION_IMMS     = "11369-6";  // History of Immunization Narrative
    public static final String SECTION_RESULTS  = "30954-2";  // Relevant diagnostic tests/laboratory data Narrative

    /** {@code http://terminology.hl7.org/CodeSystem/list-empty-reason}. */
    public static final String LIST_EMPTY_REASON = "http://terminology.hl7.org/CodeSystem/list-empty-reason";

    /** Absent/unknown coding system used when the source has no data for a section. */
    public static final String IPS_ABSENT_UNKNOWN = "http://hl7.org/fhir/uv/ips/CodeSystem/absent-unknown-uv-ips";
}
