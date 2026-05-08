package se.inera.journalen.proxy.ips;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatientSummaryMapperTest {

    private static PatientSummaryData empty() {
        return new PatientSummaryData(
                PatientSummaryService.synthesizePatient("196507132758"),
                List.of(), List.of(), List.of(), List.of(), List.of(), false);
    }

    @Test
    void buildsDocumentBundleWithCompositionFirst() {
        Bundle b = PatientSummaryMapper.toIpsBundle(empty());
        assertThat(b.getType()).isEqualTo(Bundle.BundleType.DOCUMENT);
        assertThat(b.getEntry()).isNotEmpty();
        assertThat(b.getEntryFirstRep().getResource()).isInstanceOf(Composition.class);
    }

    @Test
    void firstEntryIsIpsCompositionWithFiveSections() {
        Bundle b = PatientSummaryMapper.toIpsBundle(empty());
        Composition c = (Composition) b.getEntryFirstRep().getResource();
        assertThat(c.getMeta().getProfile()).extracting(p -> p.getValue())
                .contains(IpsProfiles.COMPOSITION);
        assertThat(c.getStatus()).isEqualTo(Composition.CompositionStatus.FINAL);
        assertThat(c.getType().getCodingFirstRep().getCode()).isEqualTo(IpsProfiles.IPS_DOC_TYPE_CODE);
        assertThat(c.getSection()).hasSize(5);
        assertThat(c.getSection()).extracting(s -> s.getCode().getCodingFirstRep().getCode())
                .containsExactly(
                        IpsProfiles.SECTION_PROBLEMS,
                        IpsProfiles.SECTION_MEDS,
                        IpsProfiles.SECTION_ALLERGIES,
                        IpsProfiles.SECTION_IMMS,
                        IpsProfiles.SECTION_RESULTS);
    }

    @Test
    void emptyDataYieldsNoKnownEntriesOrEmptyReason() {
        Bundle b = PatientSummaryMapper.toIpsBundle(empty());
        Composition c = (Composition) b.getEntryFirstRep().getResource();
        // problems / meds / allergies use noKnown entries
        assertThat(c.getSection().get(0).getEntryFirstRep().getDisplay()).isEqualTo("No known problems");
        assertThat(c.getSection().get(1).getEntryFirstRep().getDisplay()).isEqualTo("No known medications");
        assertThat(c.getSection().get(2).getEntryFirstRep().getDisplay()).isEqualTo("No known allergies");
        // immunizations and results use emptyReason
        assertThat(c.getSection().get(3).getEmptyReason().getCodingFirstRep().getCode()).isEqualTo("nilknown");
        assertThat(c.getSection().get(4).getEmptyReason().getCodingFirstRep().getCode()).isEqualTo("nilknown");
    }

    @Test
    void labsUnavailableMapsToUnavailableEmptyReason() {
        PatientSummaryData d = new PatientSummaryData(
                PatientSummaryService.synthesizePatient("196507132758"),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                /* labsUnavailable */ true);
        Bundle b = PatientSummaryMapper.toIpsBundle(d);
        Composition c = (Composition) b.getEntryFirstRep().getResource();
        assertThat(c.getSection().get(4).getEmptyReason().getCodingFirstRep().getCode()).isEqualTo("unavailable");
    }

    @Test
    void populatedDataReferencesEntriesByUrnUuid() {
        Patient p = PatientSummaryService.synthesizePatient("196507132758");
        Condition cond = new Condition();
        MedicationStatement med = new MedicationStatement();
        AllergyIntolerance allergy = new AllergyIntolerance();
        Immunization imm = new Immunization();
        Observation obs = new Observation();

        PatientSummaryData d = new PatientSummaryData(p,
                List.of(cond), List.of(med), List.of(allergy), List.of(imm), List.of(obs), false);
        Bundle b = PatientSummaryMapper.toIpsBundle(d);

        // 1 Composition + 1 Patient + 5 clinical resources = 7 entries
        assertThat(b.getEntry()).hasSize(7);
        // Every entry's fullUrl is urn:uuid:
        assertThat(b.getEntry()).allSatisfy(e ->
                assertThat(e.getFullUrl()).startsWith("urn:uuid:"));

        Composition c = (Composition) b.getEntryFirstRep().getResource();
        // Each non-empty section's first entry references a urn:uuid:
        assertThat(c.getSection().get(0).getEntryFirstRep().getReference()).startsWith("urn:uuid:");
        assertThat(c.getSection().get(0).getEntryFirstRep().getReference())
                .isEqualTo("urn:uuid:" + cond.getIdElement().getIdPart());
        assertThat(c.getSection().get(4).getEntryFirstRep().getReference())
                .isEqualTo("urn:uuid:" + obs.getIdElement().getIdPart());
    }

    @Test
    void clinicalResourcesGetIpsProfileTags() {
        Condition cond = new Condition();
        PatientSummaryData d = new PatientSummaryData(
                PatientSummaryService.synthesizePatient("196507132758"),
                List.of(cond), List.of(), List.of(), List.of(), List.of(), false);
        PatientSummaryMapper.toIpsBundle(d);
        assertThat(cond.getMeta().getProfile()).extracting(p -> p.getValue())
                .contains(IpsProfiles.CONDITION);
    }
}
