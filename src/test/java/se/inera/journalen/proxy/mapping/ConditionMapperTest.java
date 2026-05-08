package se.inera.journalen.proxy.mapping;

import org.hl7.fhir.r4.model.Condition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.DiagnosisDetail;
import se.inera.journalen.proxy.upstream.dto.ListRow;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionMapperTest {

    private final PartialViewParser parser = new PartialViewParser();

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    @Test
    void deepMappingFromSignedDetail() throws Exception {
        DiagnosisDetail d = parser.parseDiagnosisDetail(fixture("diagnosis-detail-2.html"));
        Condition c = ConditionMapper.fromDetail(d, "0b109cbb-53d5-4f1e-91cf-029c4129b41d", "Patient/me");

        assertThat(c.getIdElement().getIdPart()).isEqualTo("0b109cbb-53d5-4f1e-91cf-029c4129b41d");
        assertThat(c.getCode().getText()).isEqualTo("Persisterande förmaksflimmer");
        assertThat(c.getRecorder().getDisplay()).isEqualTo("Gösta Christensen (Läkare)");
        assertThat(c.getVerificationStatus().getCodingFirstRep().getCode()).isEqualTo("confirmed");
        ZonedDateTime expected = ZonedDateTime.of(2017, 7, 19, 11, 27, 0, 0, ZoneId.of("Europe/Stockholm"));
        assertThat(c.getRecordedDate().toInstant()).isEqualTo(expected.toInstant());
        assertThat(c.getNoteFirstRep().getText()).contains("Medicinska specialistkliniken");
        assertThat(c.getSubject().getReference()).isEqualTo("Patient/me");
        assertThat(c.getText().getStatus().toCode()).isEqualTo("generated");
    }

    @Test
    void deepMappingFromUnsignedDetail() throws Exception {
        DiagnosisDetail d = parser.parseDiagnosisDetail(fixture("diagnosis-detail-1.html"));
        Condition c = ConditionMapper.fromDetail(d, "c2eddbab-9da0-4519-b664-aaf9c8bc9442", "Patient/me");
        assertThat(c.getVerificationStatus().getCodingFirstRep().getCode()).isEqualTo("provisional");
    }

    @Test
    void shallowMappingFromListRow() throws Exception {
        ListRow row = parser.parseListRows(fixture("diagnosis-poll.html")).get(0);
        Condition c = ConditionMapper.fromListRow(row, "Patient/me");
        assertThat(c.getIdElement().getIdPart()).isEqualTo("b79275b0-c38f-4631-ad8b-b17676dc1380");
        assertThat(c.getCode().getText()).contains("Psykiska störningar");
        assertThat(c.getRecorder().getDisplay()).isEqualTo("Åke Lövenhed (Läkare)");
    }
}
