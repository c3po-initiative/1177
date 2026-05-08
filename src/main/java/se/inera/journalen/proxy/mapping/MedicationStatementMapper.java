package se.inera.journalen.proxy.mapping;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;

import java.util.Date;

import se.inera.journalen.proxy.upstream.dto.MedicationDetail;

/**
 * Deep mapping for {@link MedicationStatement} from a parsed
 * {@link MedicationDetail}.
 *
 * Codes:
 * <ul>
 *   <li>{@link #ATC_SYSTEM} (WHO ATC) — extracted from the "Aktiv substans" row.</li>
 *   <li>{@link #NPL_PRODUCT_SYSTEM} — Swedish National Drug Catalogue (NPL) product ID,
 *       extracted from the "Produktnamn" row. The OID below is the registered NPL pack
 *       OID; the row in fact carries an NPL <em>identifier</em> rather than a pack id, so
 *       this is best-effort and primarily used for display in clients that recognize NPL.</li>
 * </ul>
 */
public final class MedicationStatementMapper {

    public static final String ATC_SYSTEM = "http://www.whocc.no/atc";
    public static final String NPL_PRODUCT_SYSTEM = "urn:oid:1.2.752.96.1.1.18";

    private MedicationStatementMapper() {}

    public static MedicationStatement fromDetail(MedicationDetail d, String id, String patientReference) {
        MedicationStatement m = new MedicationStatement();
        m.setId(id);
        m.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
        m.setSubject(new Reference(patientReference));

        m.setMedication(buildMedicationCoding(d));

        if (d.prescriptionTime != null) {
            Date when = DateUtil.parseDateTime(d.prescriptionTime);
            if (when != null) m.setDateAsserted(when);
        }

        // Effective period: start required for a Period; otherwise fall back to a single dateTime.
        if (d.treatmentPeriodStart != null) {
            Period period = new Period();
            Date start = DateUtil.parseDate(d.treatmentPeriodStart);
            if (start != null) period.setStart(start);
            if (d.treatmentPeriodEnd != null) {
                Date end = DateUtil.parseDate(d.treatmentPeriodEnd);
                if (end != null) period.setEnd(end);
            }
            if (period.hasStart()) m.setEffective(period);
        } else if (d.prescriptionTime != null) {
            Date when = DateUtil.parseDateTime(d.prescriptionTime);
            if (when != null) m.setEffective(new DateTimeType(when));
        }

        if (d.prescriptionReason != null) {
            m.addReasonCode(new CodeableConcept().setText(d.prescriptionReason));
        }

        if (d.dosageInstruction != null || d.routeOfAdministration != null
                || d.formAndStrength != null) {
            Dosage dosage = m.addDosage();
            if (d.dosageInstruction != null) dosage.setText(d.dosageInstruction);
            if (d.routeOfAdministration != null) {
                dosage.setRoute(new CodeableConcept().setText(d.routeOfAdministration));
            }
        }

        // Source of the information: the prescriber. Care unit goes into a note.
        String prescriberDisplay = preferred(d.asserterName, d.registeredByName);
        if (prescriberDisplay != null) {
            String role = d.asserterRole;
            String display = role != null ? prescriberDisplay + " (" + role + ")" : prescriberDisplay;
            m.setInformationSource(new Reference().setDisplay(display));
        }
        String careUnit = preferred(d.careUnit, d.registeredByCareUnit);
        if (careUnit != null) {
            m.addNote().setText(careUnit);
        }
        if (d.formAndStrength != null) {
            m.addNote().setText("Form och styrka: " + d.formAndStrength);
        }

        if (d.html != null) {
            Narrative n = new Narrative();
            n.setStatus(Narrative.NarrativeStatus.GENERATED);
            n.setDivAsString(NarrativeUtil.wrap(d.html));
            m.setText(n);
        }
        return m;
    }

    private static CodeableConcept buildMedicationCoding(MedicationDetail d) {
        CodeableConcept code = new CodeableConcept();
        // ATC first — internationally recognized.
        if (d.atcCode != null) {
            code.addCoding(new Coding()
                    .setSystem(ATC_SYSTEM)
                    .setCode(d.atcCode)
                    .setDisplay(d.activeSubstanceName != null ? d.activeSubstanceName : d.medicationName));
        }
        // Then NPL product ID (Swedish national drug catalog).
        if (d.nplProductId != null) {
            code.addCoding(new Coding()
                    .setSystem(NPL_PRODUCT_SYSTEM)
                    .setCode(d.nplProductId)
                    .setDisplay(d.productName != null ? d.productName : d.medicationName));
        }
        // Always set text for display fallback.
        String text = d.medicationName != null ? d.medicationName
                : (d.productName != null ? d.productName : d.activeSubstanceName);
        if (text != null) code.setText(text);
        return code;
    }

    private static String preferred(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b != null && !b.isEmpty() ? b : null);
    }
}
