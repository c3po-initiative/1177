package se.inera.journalen.proxy.mapping;

import org.hl7.fhir.r4.model.ServiceRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.ServiceRequestDetail;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceRequestMapperTest {

    private final PartialViewParser parser = new PartialViewParser();

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    @Test
    void parsesSenderAndStatusTimeline() throws Exception {
        ServiceRequestDetail d = parser.parseServiceRequestDetail(fixture("servicerequest-detail.html"));
        assertThat(d.title).isEqualTo("Remiss");
        assertThat(d.timestamp).isEqualTo("2025-10-09 15:43");
        assertThat(d.sender).contains("Avdelning 12 Hud");
        assertThat(d.statusTimeline).hasSizeGreaterThanOrEqualTo(1);
        var first = d.statusTimeline.get(0);
        assertThat(first.date).isEqualTo("2025-10-09");
        assertThat(first.status).isEqualTo("Accepterad");
        assertThat(first.byUnit).contains("Dialysmottagning Falun");
    }

    @Test
    void mapsLatestStatusToFhirActiveAndCarriesTimelineAsNotes() throws Exception {
        ServiceRequestDetail d = parser.parseServiceRequestDetail(fixture("servicerequest-detail.html"));
        ServiceRequest sr = ServiceRequestMapper.fromDetail(d, "sr-1", "Patient/me");
        assertThat(sr.getStatus()).isEqualTo(ServiceRequest.ServiceRequestStatus.ACTIVE);
        assertThat(sr.getCode().getText()).isEqualTo("Remiss");
        assertThat(sr.getRequester().getDisplay()).contains("Avdelning 12 Hud");
        assertThat(sr.getNote()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(sr.getNoteFirstRep().getText()).contains("Accepterad");
    }
}
