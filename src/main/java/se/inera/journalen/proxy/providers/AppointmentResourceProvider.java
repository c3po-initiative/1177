package se.inera.journalen.proxy.providers;

import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import com.fasterxml.jackson.databind.JsonNode;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Bundle;

import java.io.IOException;

import se.inera.journalen.proxy.mapping.AppointmentMapper;
import se.inera.journalen.proxy.server.AuthContext;
import se.inera.journalen.proxy.server.BokadetiderSessionCache;
import se.inera.journalen.proxy.upstream.BokadetiderClient;

/**
 * Surfaces the {@code bokadetider.at.1177.se/api/appointments} feed as FHIR Appointment.
 *
 * Authenticates lazily on first call: the bokadetider service is a separate Shibboleth SP that
 * shares the same Inera IDP as journalen. The user's HTTP Basic credentials are reused for the
 * second SP login. The per-credentials BokadetiderClient is cached for 10 minutes via
 * {@link BokadetiderSessionCache}.
 */
public class AppointmentResourceProvider implements IResourceProvider {

    private final BokadetiderSessionCache cache;
    private final String idpBase;
    private final String bokadetiderBase;

    public AppointmentResourceProvider(BokadetiderSessionCache cache,
                                       String idpBase, String bokadetiderBase) {
        this.cache = cache;
        this.idpBase = idpBase;
        this.bokadetiderBase = bokadetiderBase;
    }

    @Override
    public Class<Appointment> getResourceType() {
        return Appointment.class;
    }

    @Search
    public Bundle search(@OptionalParam(name = "patient") ReferenceParam patient,
                         RequestDetails req) {
        String identifier = AuthContext.identifier(req);
        String password = AuthContext.password(req);
        if (identifier == null || password == null) {
            throw new AuthenticationException("Appointment search needs the same Basic credentials used for the proxy.");
        }
        try {
            BokadetiderClient client = cache.acquire(identifier, password,
                    () -> new BokadetiderClient(idpBase, bokadetiderBase));
            JsonNode appts = client.getJson("/api/appointments");

            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
            String patientRef = AuthContext.patientReference(req);
            int count = 0;
            if (appts.isArray()) {
                for (JsonNode node : appts) {
                    Appointment a = AppointmentMapper.fromJson(node, patientRef);
                    bundle.addEntry()
                            .setFullUrl(req.getFhirServerBase() + "/Appointment/" + a.getIdElement().getIdPart())
                            .setResource(a);
                    count++;
                }
            }
            bundle.setTotal(count);
            return bundle;
        } catch (IOException e) {
            throw new InternalErrorException("Bokadetider call failed: " + e.getMessage(), e);
        }
    }
}
