package se.inera.journalen.proxy.mapping;

import org.hl7.fhir.r4.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.ListRow;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventMapperTest {

    private final PartialViewParser parser = new PartialViewParser();

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    @Test
    void parsesUserAccessLogRowsAsAuditEvents() throws Exception {
        List<ListRow> rows = parser.parseListRows(fixture("user-access-logs.html"));
        assertThat(rows).isNotEmpty();
        ListRow first = rows.get(0);
        // data-id is numeric for these endpoints (not a UUID)
        assertThat(first.id).matches("\\d+");
        // data-date carries the full datetime
        assertThat(first.date).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
        // .AccessedBy → row.authorName
        assertThat(first.authorName).contains("Testperson");
    }

    @Test
    void mapsToAuditEventWithRecordedTimestampAndAgent() throws Exception {
        ListRow row = parser.parseListRows(fixture("user-access-logs.html")).get(0);
        AuditEvent a = SkeletonMappers.auditEvent(row, "Patient/me");
        assertThat(a.getIdElement().getIdPart()).isEqualTo(row.id);
        assertThat(a.getAction()).isEqualTo(AuditEvent.AuditEventAction.R);
        assertThat(a.getOutcome()).isEqualTo(AuditEvent.AuditEventOutcome._0);
        assertThat(a.getRecorded()).isNotNull();
        // Stockholm-local datetime parse
        ZonedDateTime expected = ZonedDateTime.of(2026, 5, 8, 12, 29, 0, 0, ZoneId.of("Europe/Stockholm"));
        assertThat(a.getRecorded().toInstant()).isEqualTo(expected.toInstant());
        // Agent name comes from the .AccessedBy text
        assertThat(a.getAgentFirstRep().getName()).contains("Testperson");
        assertThat(a.getAgentFirstRep().getRequestor()).isTrue();
        // Entity references the patient and is typed as Patient/Person
        assertThat(a.getEntityFirstRep().getWhat().getReference()).isEqualTo("Patient/me");
        assertThat(a.getEntityFirstRep().getRole().getCode()).isEqualTo("1");
    }
}
