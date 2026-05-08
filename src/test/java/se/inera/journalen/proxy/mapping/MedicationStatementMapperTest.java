package se.inera.journalen.proxy.mapping;

import org.hl7.fhir.r4.model.MedicationStatement;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.MedicationDetail;

import static org.assertj.core.api.Assertions.assertThat;

class MedicationStatementMapperTest {

    private final PartialViewParser parser = new PartialViewParser();

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    @Test
    void parsesMatrifenWithFullDetails() throws Exception {
        MedicationDetail d = parser.parseMedicationDetail(fixture("medication-detail-matrifen.html"));
        assertThat(d.medicationName).isEqualTo("Matrifen");
        assertThat(d.timestamp).isEqualTo("2025-11-11 08:23");
        assertThat(d.formAndStrength).isEqualTo("Depotplåster 25 mikrog/timme");
        assertThat(d.dosageInstruction).contains("1 plåster var tredje dag");
        assertThat(d.prescriptionTime).isEqualTo("2025-11-11 08:23");
        assertThat(d.treatmentPeriodStart).isEqualTo("2025-11-11");
        assertThat(d.treatmentPeriodEnd).isNull();        // open-ended ("till saknas")
        assertThat(d.prescriptionReason).isEqualTo("Mot smärta");
        assertThat(d.atcCode).isEqualTo("N02AB03");
        assertThat(d.activeSubstanceName).isEqualTo("Fentanyl");
        assertThat(d.nplProductId).isEqualTo("20040916000804");
        assertThat(d.productName).isEqualTo("Matrifen");
        assertThat(d.routeOfAdministration).isEqualTo("kutant");
    }

    @Test
    void parsesEliquisAndLevaxin() throws Exception {
        MedicationDetail eliquis = parser.parseMedicationDetail(fixture("medication-detail-eliquis.html"));
        assertThat(eliquis.medicationName).isEqualTo("Eliquis");
        assertThat(eliquis.formAndStrength).isEqualTo("Filmdragerad tablett 2,5 mg");
        assertThat(eliquis.dosageInstruction).contains("1 tablett");
        assertThat(eliquis.atcCode).isNotBlank();

        MedicationDetail levaxin = parser.parseMedicationDetail(fixture("medication-detail-levaxin.html"));
        assertThat(levaxin.medicationName).isEqualTo("Levaxin");
        assertThat(levaxin.formAndStrength).isEqualTo("Tablett 125 mikrog");
    }

    @Test
    void mapperBuildsMedicationStatementWithAtcAndNplCodings() throws Exception {
        MedicationDetail d = parser.parseMedicationDetail(fixture("medication-detail-matrifen.html"));
        MedicationStatement m = MedicationStatementMapper.fromDetail(d, "med-1", "Patient/me");

        assertThat(m.getIdElement().getIdPart()).isEqualTo("med-1");
        assertThat(m.getStatus()).isEqualTo(MedicationStatement.MedicationStatementStatus.ACTIVE);
        assertThat(m.getSubject().getReference()).isEqualTo("Patient/me");

        var coding = m.getMedicationCodeableConcept();
        assertThat(coding.getText()).isEqualTo("Matrifen");
        assertThat(coding.getCoding()).extracting(c -> c.getSystem())
                .contains(MedicationStatementMapper.ATC_SYSTEM,
                          MedicationStatementMapper.NPL_PRODUCT_SYSTEM);
        assertThat(coding.getCoding().stream()
                .filter(c -> MedicationStatementMapper.ATC_SYSTEM.equals(c.getSystem()))
                .findFirst().get().getCode()).isEqualTo("N02AB03");

        // Effective period: start=2025-11-11 (Stockholm), no end
        assertThat(m.hasEffectivePeriod()).isTrue();
        ZonedDateTime expectedStart = ZonedDateTime.of(2025, 11, 11, 0, 0, 0, 0, ZoneId.of("Europe/Stockholm"));
        assertThat(m.getEffectivePeriod().getStart().toInstant()).isEqualTo(expectedStart.toInstant());
        assertThat(m.getEffectivePeriod().hasEnd()).isFalse();

        assertThat(m.getDateAsserted()).isNotNull();
        assertThat(m.getReasonCodeFirstRep().getText()).isEqualTo("Mot smärta");
        assertThat(m.getDosageFirstRep().getText()).contains("1 plåster var tredje dag");
        assertThat(m.getDosageFirstRep().getRoute().getText()).isEqualTo("kutant");
        assertThat(m.getInformationSource().getDisplay()).contains("Bergwall, Stiina");
        assertThat(m.getNote()).extracting(n -> n.getText())
                .anySatisfy(s -> assertThat(s).contains("Form och styrka"));
        assertThat(m.getText().getDiv()).isNotNull();
    }
}
