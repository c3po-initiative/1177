package se.inera.journalen.proxy.providers;

import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.RelatedPerson;

import java.io.IOException;

import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.server.AuthContext;
import se.inera.journalen.proxy.upstream.InvanarClient;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;
import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.PollEnvelope;

public class RelatedPersonResourceProvider implements IResourceProvider {

    @Override
    public Class<RelatedPerson> getResourceType() {
        return RelatedPerson.class;
    }

    @Search
    public Bundle search(@OptionalParam(name = "patient") ReferenceParam patient,
                         RequestDetails req) {
        InvanarClient client = AuthContext.client(req);
        PartialViewParser parser = new PartialViewParser();
        try {
            // GetLegalRepresentation takes an empty body.
            PollEnvelope env = client.postJson(InvanarEndpoints.LEGAL_REPRESENTATION, java.util.Map.of());
            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
            String patientRef = AuthContext.patientReference(req);
            int n = 0;
            for (var row : parser.parseListRows(env.htmlBody())) {
                RelatedPerson rp = SkeletonMappers.relatedPerson(row, patientRef);
                bundle.addEntry()
                        .setFullUrl(req.getFhirServerBase() + "/RelatedPerson/" + rp.getIdElement().getIdPart())
                        .setResource(rp);
                n++;
            }
            bundle.setTotal(n);
            return bundle;
        } catch (IOException e) {
            throw new InternalErrorException("Upstream legal representation failed: " + e.getMessage(), e);
        }
    }
}
