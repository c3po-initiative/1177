package se.inera.journalen.proxy.upstream;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import se.inera.journalen.proxy.upstream.dto.DiagnosisDetail;
import se.inera.journalen.proxy.upstream.dto.ListRow;

import static org.assertj.core.api.Assertions.assertThat;

class PartialViewParserTest {

    private final PartialViewParser parser = new PartialViewParser();

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    @Test
    void parsesAllFourDiagnosisRows() throws Exception {
        String html = fixture("diagnosis-poll.html");
        List<ListRow> rows = parser.parseListRows(html);
        assertThat(rows).hasSize(4);
    }

    @Test
    void firstDiagnosisRowHasExpectedFields() throws Exception {
        String html = fixture("diagnosis-poll.html");
        ListRow first = parser.parseListRows(html).get(0);
        assertThat(first.id).isEqualTo("b79275b0-c38f-4631-ad8b-b17676dc1380");
        assertThat(first.date).isEqualTo("2019-08-30");
        assertThat(first.authorName).isEqualTo("Åke Lövenhed (Läkare)");
        assertThat(first.careUnit).contains("Psykiatriska kliniken");
        assertThat(first.ariaLabel).contains("antecknad av Åke Lövenhed");
    }

    @Test
    void diagnosisFromAriaLabelExtracts() {
        String aria = "Datum 2017-07-19, diagnos Renovaskulär hypertoni, antecknad av Gösta Christensen, (Läkare), på Medicinska...";
        assertThat(PartialViewParser.diagnosisFromAriaLabel(aria))
                .isEqualTo("Renovaskulär hypertoni");
    }

    @Test
    void parsesSignedDiagnosisDetail() throws Exception {
        String html = fixture("diagnosis-detail-2.html");
        DiagnosisDetail d = parser.parseDiagnosisDetail(html);
        assertThat(d.headingName).isEqualTo("Persisterande förmaksflimmer");
        assertThat(d.mainDiagnosis).isEqualTo("Persisterande förmaksflimmer");
        assertThat(d.timestamp).isEqualTo("2017-07-19 11:27");
        assertThat(d.asserterName).isEqualTo("Gösta Christensen");
        assertThat(d.asserterRole).isEqualTo("Läkare");
        assertThat(d.careUnit).contains("Medicinska specialistkliniken");
        assertThat(d.signed).isTrue();
    }

    @Test
    void parsesUnsignedDiagnosisDetail() throws Exception {
        String html = fixture("diagnosis-detail-1.html");
        DiagnosisDetail d = parser.parseDiagnosisDetail(html);
        assertThat(d.signed).isFalse();
        assertThat(d.asserterName).isEqualTo("Pernilla Rask");
        assertThat(d.asserterRole).isEqualTo("Läkare");
        assertThat(d.timestamp).isEqualTo("2014-06-17 13:12");
    }

    @Test
    void parsesReferralStatusList() throws Exception {
        String html = fixture("referralStatus-poll.html");
        List<ListRow> rows = parser.parseListRows(html);
        // The HAR captured 1 row in this fixture; just assert non-zero and that ids look like UUIDs.
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).id).matches("[0-9a-f-]+");
    }

    @Test
    void parsesJournalTimelineRows() throws Exception {
        String html = fixture("journaloverview-timeline.html");
        List<ListRow> rows = parser.parseListRows(html);
        // Page size in HAR was 10
        assertThat(rows.size()).isGreaterThanOrEqualTo(1);
        assertThat(rows.get(0).id).isNotBlank();
    }
}
