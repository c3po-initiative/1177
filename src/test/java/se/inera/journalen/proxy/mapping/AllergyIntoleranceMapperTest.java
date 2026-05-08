package se.inera.journalen.proxy.mapping;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.AllergyDetail;

import static org.assertj.core.api.Assertions.assertThat;

class AllergyIntoleranceMapperTest {

    private final PartialViewParser parser = new PartialViewParser();

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    @Test
    void parsesAllergyDetailFields() throws Exception {
        AllergyDetail d = parser.parseAllergyDetail(fixture("allergy-detail.html"));
        assertThat(d.title).isEqualTo("Överkänslighet");
        assertThat(d.allergen).isEqualTo("Fisk");
        assertThat(d.severity).isEqualTo("Besvärande");
        assertThat(d.certainty).isEqualTo("Bekräftad");
        assertThat(d.activeRaw).isEqualTo("Ja");
        assertThat(d.signedRaw).contains("Åke Lövenhed");
        assertThat(d.timestamp).isEqualTo("2019-09-11 10:41");
        assertThat(d.asserterName).isEqualTo("Åke Lövenhed");
        assertThat(d.asserterRole).isEqualTo("Läkare");
    }

    @Test
    void mapperBuildsAllergyIntoleranceWithStatusesAndCriticality() throws Exception {
        AllergyDetail d = parser.parseAllergyDetail(fixture("allergy-detail.html"));
        AllergyIntolerance a = AllergyIntoleranceMapper.fromDetail(d, "all-1", "Patient/me");
        assertThat(a.getCode().getText()).isEqualTo("Fisk");
        assertThat(a.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("active");
        assertThat(a.getVerificationStatus().getCodingFirstRep().getCode()).isEqualTo("confirmed");
        assertThat(a.getCriticality()).isEqualTo(AllergyIntolerance.AllergyIntoleranceCriticality.LOW);
        assertThat(a.getReactionFirstRep().getManifestationFirstRep().getText()).isEqualTo("Överkänslighet");
        assertThat(a.getReactionFirstRep().getSeverity())
                .isEqualTo(AllergyIntolerance.AllergyIntoleranceSeverity.MODERATE);
        assertThat(a.getRecordedDate()).isNotNull();
        assertThat(a.getAsserter().getDisplay()).contains("Åke Lövenhed");
    }
}
