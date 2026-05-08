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
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.ServiceRequest;

import java.io.IOException;

import se.inera.journalen.proxy.mapping.ServiceRequestMapper;
import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.server.AuthContext;
import se.inera.journalen.proxy.upstream.InvanarClient;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;
import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.PollEnvelope;
import se.inera.journalen.proxy.upstream.dto.ServiceRequestDetail;

public class ServiceRequestResourceProvider implements IResourceProvider {

    private final PartialViewParser parser = new PartialViewParser();

    @Override
    public Class<ServiceRequest> getResourceType() {
        return ServiceRequest.class;
    }

    @Read
    public ServiceRequest read(@IdParam IdType id, RequestDetails req) {
        InvanarClient client = AuthContext.client(req);
        try {
            PollEnvelope env = client.detail(InvanarEndpoints.REFERRAL_DETAIL, id.getIdPart());
            if (env.htmlBody().isEmpty()) {
                throw new ResourceNotFoundException(id);
            }
            ServiceRequestDetail d = parser.parseServiceRequestDetail(env.htmlBody());
            return ServiceRequestMapper.fromDetail(d, id.getIdPart(), AuthContext.patientReference(req));
        } catch (IOException e) {
            throw new InternalErrorException("Upstream referralStatus/detailview failed: " + e.getMessage(), e);
        }
    }

    @Search
    public Bundle search(@OptionalParam(name = "patient") ReferenceParam patient,
                         @Count Integer count,
                         @Offset Integer offset,
                         RequestDetails req) {
        return SkeletonProviderSupport.pollAndMap(
                InvanarEndpoints.REFERRAL_POLL, "ServiceRequest", count, offset, req,
                SkeletonMappers::serviceRequest);
    }
}
