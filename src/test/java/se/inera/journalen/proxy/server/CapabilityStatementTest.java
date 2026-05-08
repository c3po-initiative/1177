package se.inera.journalen.proxy.server;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStatementTest {

    private static Server server;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        server = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        server.setHandler(ctx);
        ctx.addServlet(new ServletHolder(new ProxyRestfulServer(
                "https://idp.example.invalid", "https://journalen.example.invalid")), "/fhir/*");
        server.start();
        port = ((org.eclipse.jetty.server.ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) server.stop();
    }

    @Test
    void metadataListsAllResourceTypes() throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/fhir/metadata"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);

        IParser p = FhirContext.forR4().newJsonParser();
        CapabilityStatement cs = p.parseResource(CapabilityStatement.class, resp.body());
        Set<String> types = cs.getRestFirstRep().getResource().stream()
                .map(r -> r.getType()).collect(Collectors.toSet());

        assertThat(types).contains(
                "Patient", "Condition", "ServiceRequest", "CarePlan",
                "Immunization", "Observation", "DiagnosticReport",
                "DocumentReference", "RelatedPerson",
                "AllergyIntolerance", "MedicationStatement", "Encounter",
                "AuditEvent", "Consent", "Appointment", "Communication");
    }
}
