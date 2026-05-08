package se.inera.journalen.proxy.mapping;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Reference;

import java.util.Date;
import java.util.Locale;

import se.inera.journalen.proxy.upstream.dto.AllergyDetail;

/**
 * Deep mapping for {@link AllergyIntolerance} from the parsed
 * {@link AllergyDetail}.
 *
 * Swedish-to-FHIR translations:
 * <ul>
 *   <li>"Aktuell: Ja" → {@code clinicalStatus = active}; otherwise {@code inactive}</li>
 *   <li>"Visshetsgrad: Bekräftad" → {@code verificationStatus = confirmed};
 *       "Misstänkt" → {@code unconfirmed}; "Vederlagd"/"Avförd" → {@code refuted}</li>
 *   <li>"Allvarlighetsgrad" → {@code criticality}: Allvarlig → high; Besvärande/Lindrig → low</li>
 * </ul>
 */
public final class AllergyIntoleranceMapper {

    private static final String CLINICAL_SYSTEM = "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical";
    private static final String VERIFICATION_SYSTEM = "http://terminology.hl7.org/CodeSystem/allergyintolerance-verification";

    private AllergyIntoleranceMapper() {}

    public static AllergyIntolerance fromDetail(AllergyDetail d, String id, String patientReference) {
        AllergyIntolerance a = new AllergyIntolerance();
        a.setId(id);
        a.setPatient(new Reference(patientReference));

        // Clinical status
        boolean active = d.activeRaw == null || d.activeRaw.equalsIgnoreCase("Ja");
        a.setClinicalStatus(new CodeableConcept().addCoding(new Coding()
                .setSystem(CLINICAL_SYSTEM)
                .setCode(active ? "active" : "inactive")
                .setDisplay(active ? "Active" : "Inactive")));

        // Verification status
        a.setVerificationStatus(new CodeableConcept().addCoding(verificationFor(d.certainty)));

        // Criticality
        AllergyIntolerance.AllergyIntoleranceCriticality crit = criticalityFor(d.severity);
        if (crit != null) a.setCriticality(crit);

        // The allergen text — drives Code. Falls back to the heading (which is the reaction type).
        String allergen = d.allergen != null ? d.allergen : d.title;
        if (allergen != null) {
            a.setCode(new CodeableConcept().setText(allergen));
        }

        // The heading is the reaction type itself ("Överkänslighet"). Map to reaction[0].manifestation.
        if (d.title != null) {
            AllergyIntolerance.AllergyIntoleranceReactionComponent reaction = a.addReaction();
            reaction.addManifestation(new CodeableConcept().setText(d.title));
            if (d.severity != null) {
                AllergyIntolerance.AllergyIntoleranceSeverity rsev = reactionSeverityFor(d.severity);
                if (rsev != null) reaction.setSeverity(rsev);
            }
        }

        // Recorded date / asserter
        if (d.timestamp != null) {
            Date when = DateUtil.parseDateTime(d.timestamp);
            if (when != null) a.setRecordedDate(when);
        }
        if (d.asserterName != null) {
            String display = d.asserterRole != null
                    ? d.asserterName + " (" + d.asserterRole + ")"
                    : d.asserterName;
            a.setAsserter(new Reference().setDisplay(display));
        }
        if (d.careUnit != null) a.addNote().setText(d.careUnit);
        if (d.signedRaw != null) a.addNote().setText("Signerad: " + d.signedRaw);
        if (d.validityRaw != null) a.addNote().setText("Giltighetstid: " + d.validityRaw);

        if (d.html != null) {
            Narrative n = new Narrative();
            n.setStatus(Narrative.NarrativeStatus.GENERATED);
            n.setDivAsString(NarrativeUtil.wrap(d.html));
            a.setText(n);
        }
        return a;
    }

    private static Coding verificationFor(String certainty) {
        Coding c = new Coding().setSystem(VERIFICATION_SYSTEM);
        if (certainty == null) return c.setCode("unconfirmed").setDisplay("Unconfirmed");
        String t = certainty.toLowerCase(Locale.ROOT);
        if (t.contains("bekr"))      return c.setCode("confirmed").setDisplay("Confirmed");
        if (t.contains("misst"))     return c.setCode("unconfirmed").setDisplay("Unconfirmed");
        if (t.contains("avf") || t.contains("vederl")) return c.setCode("refuted").setDisplay("Refuted");
        if (t.contains("fel"))       return c.setCode("entered-in-error").setDisplay("Entered in Error");
        return c.setCode("unconfirmed").setDisplay("Unconfirmed");
    }

    private static AllergyIntolerance.AllergyIntoleranceCriticality criticalityFor(String severity) {
        if (severity == null) return null;
        String t = severity.toLowerCase(Locale.ROOT);
        if (t.contains("allvarlig") || t.contains("livshot")) return AllergyIntolerance.AllergyIntoleranceCriticality.HIGH;
        if (t.contains("besv") || t.contains("lindrig"))      return AllergyIntolerance.AllergyIntoleranceCriticality.LOW;
        return null;
    }

    private static AllergyIntolerance.AllergyIntoleranceSeverity reactionSeverityFor(String severity) {
        if (severity == null) return null;
        String t = severity.toLowerCase(Locale.ROOT);
        if (t.contains("allvarlig") || t.contains("livshot")) return AllergyIntolerance.AllergyIntoleranceSeverity.SEVERE;
        if (t.contains("besv"))   return AllergyIntolerance.AllergyIntoleranceSeverity.MODERATE;
        if (t.contains("lindrig"))return AllergyIntolerance.AllergyIntoleranceSeverity.MILD;
        return null;
    }
}
