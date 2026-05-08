package se.inera.journalen.proxy.providers;

import ca.uhn.fhir.rest.annotation.Count;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Offset;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.IdType;

import java.io.IOException;

import se.inera.journalen.proxy.mapping.CommunicationMapper;
import se.inera.journalen.proxy.server.AuthContext;
import se.inera.journalen.proxy.server.ETjansterSessionCache;
import se.inera.journalen.proxy.upstream.ETjansterClient;

/**
 * Surfaces the {@code e-tjanster.at.1177.se/api/core/inbox/message} feed as FHIR Communication.
 * Search returns one Communication per inbox message; read fetches the full message detail
 * (including the {@code messageText} HTML body which the list view omits).
 *
 * Authenticates lazily on first request via {@link ETjansterSessionCache} using the same
 * Basic credentials the caller is already presenting to the proxy.
 */
public class CommunicationResourceProvider implements IResourceProvider {

    private final ETjansterSessionCache cache;
    private final String idpBase;
    private final String spBase;

    public CommunicationResourceProvider(ETjansterSessionCache cache, String idpBase, String spBase) {
        this.cache = cache;
        this.idpBase = idpBase;
        this.spBase = spBase;
    }

    @Override
    public Class<Communication> getResourceType() {
        return Communication.class;
    }

    @Read
    public Communication read(@IdParam IdType id, RequestDetails req) {
        ETjansterClient client = client(req);
        try {
            JsonNode node = client.getJson("/api/core/inbox/message/" + id.getIdPart());
            // The detail endpoint returns either a single object or a list (thread). Pick the
            // entry whose id matches; otherwise the first.
            JsonNode picked = node;
            if (node.isArray()) {
                String wanted = id.getIdPart();
                for (JsonNode m : node) {
                    if (wanted.equals(m.path("id").asText())) { picked = m; break; }
                }
                if (picked.isArray() && picked.size() > 0) picked = picked.get(0);
            }
            if (picked == null || picked.isMissingNode() || picked.isArray()) {
                throw new ResourceNotFoundException(id);
            }
            return CommunicationMapper.fromJson(picked, AuthContext.patientReference(req));
        } catch (IOException e) {
            throw new InternalErrorException("E-tjänster message detail failed: " + e.getMessage(), e);
        }
    }

    @Search
    public Bundle search(@OptionalParam(name = "patient") ReferenceParam patient,
                         @Count Integer count,
                         @Offset Integer offset,
                         RequestDetails req) {
        ETjansterClient client = client(req);
        int take = count == null ? 50 : Math.min(count, 200);
        int skip = offset == null ? 0 : offset;
        try {
            JsonNode list = client.getJson("/api/core/inbox/message");
            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
            String patientRef = AuthContext.patientReference(req);
            int total = 0;
            int emitted = 0;
            if (list.isArray()) {
                total = list.size();
                int idx = 0;
                for (JsonNode node : list) {
                    if (idx++ < skip) continue;
                    if (emitted >= take) break;
                    Communication c = CommunicationMapper.fromJson(node, patientRef);
                    bundle.addEntry()
                            .setFullUrl(req.getFhirServerBase() + "/Communication/" + c.getIdElement().getIdPart())
                            .setResource(c);
                    emitted++;
                }
            }
            bundle.setTotal(total);
            return bundle;
        } catch (IOException e) {
            throw new InternalErrorException("E-tjänster inbox poll failed: " + e.getMessage(), e);
        }
    }

    private ETjansterClient client(RequestDetails req) {
        String identifier = AuthContext.identifier(req);
        String password = AuthContext.password(req);
        if (identifier == null || password == null) {
            throw new AuthenticationException("Communication search needs the same Basic credentials used for the proxy.");
        }
        try {
            return cache.acquire(identifier, password,
                    () -> new ETjansterClient(idpBase, spBase));
        } catch (IOException e) {
            throw new InternalErrorException("E-tjänster login failed: " + e.getMessage(), e);
        }
    }
}
