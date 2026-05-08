package se.inera.journalen.proxy.mapping;

import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Reference;

import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.DiagnosisDetail;
import se.inera.journalen.proxy.upstream.dto.ListRow;

public final class ConditionMapper {

    private static final String VS_VERIFICATION = "http://terminology.hl7.org/CodeSystem/condition-ver-status";

    private ConditionMapper() {}

    /**
     * Builds a shallow Condition from a list-row (no detailview round-trip).
     * code.text comes from the aria-label fragment.
     */
    public static Condition fromListRow(ListRow row, String patientReference) {
        Condition c = new Condition();
        c.setId(row.id);
        c.setSubject(new Reference(patientReference));

        String name = PartialViewParser.diagnosisFromAriaLabel(row.ariaLabel);
        if (name != null) {
            c.setCode(new CodeableConcept().setText(name));
        }

        if (row.date != null && !row.date.isBlank()) {
            c.setRecordedDate(DateUtil.parseDate(row.date));
        }

        if (row.authorName != null) {
            c.setRecorder(new Reference().setDisplay(row.authorName));
        }

        if (row.html != null) {
            Narrative n = new Narrative();
            n.setStatus(Narrative.NarrativeStatus.GENERATED);
            n.setDivAsString(NarrativeUtil.wrap(row.html));
            c.setText(n);
        }
        return c;
    }

    /** Builds a deep Condition from a detailview response. */
    public static Condition fromDetail(DiagnosisDetail detail, String id, String patientReference) {
        Condition c = new Condition();
        c.setId(id);
        c.setSubject(new Reference(patientReference));

        String text = detail.mainDiagnosis != null ? detail.mainDiagnosis : detail.headingName;
        if (text != null) {
            c.setCode(new CodeableConcept().setText(text));
        }

        if (detail.timestamp != null) {
            c.setRecordedDate(DateUtil.parseDateTime(detail.timestamp));
        }

        Coding verification = new Coding()
                .setSystem(VS_VERIFICATION)
                .setCode(detail.signed ? "confirmed" : "provisional")
                .setDisplay(detail.signed ? "Confirmed" : "Provisional");
        c.setVerificationStatus(new CodeableConcept().addCoding(verification));

        if (detail.asserterName != null) {
            String display = detail.asserterRole != null
                    ? detail.asserterName + " (" + detail.asserterRole + ")"
                    : detail.asserterName;
            c.setRecorder(new Reference().setDisplay(display));
        }

        if (detail.careUnit != null) {
            c.addNote().setText(detail.careUnit);
        }

        if (detail.html != null) {
            Narrative n = new Narrative();
            n.setStatus(Narrative.NarrativeStatus.GENERATED);
            n.setDivAsString(NarrativeUtil.wrap(detail.html));
            c.setText(n);
        }
        return c;
    }
}
