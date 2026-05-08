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
import org.hl7.fhir.r4.model.MedicationStatement;

import java.io.IOException;

import se.inera.journalen.proxy.mapping.MedicationStatementMapper;
import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.server.AuthContext;
import se.inera.journalen.proxy.upstream.InvanarClient;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;
import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.MedicationDetail;
import se.inera.journalen.proxy.upstream.dto.PollEnvelope;

public class MedicationStatementResourceProvider implements IResourceProvider {

    private final PartialViewParser parser = new PartialViewParser();

    @Override
    public Class<MedicationStatement> getResourceType() {
        return MedicationStatement.class;
    }

    /**
     * Deep read: fetches the medication detail page and pulls structured fields
     * (ATC code, NPL product id, dosage instruction, route, treatment period, reason)
     * via {@link MedicationStatementMapper#fromDetail}.
     */
    @Read
    public MedicationStatement read(@IdParam IdType id, RequestDetails req) {
        InvanarClient client = AuthContext.client(req);
        try {
            PollEnvelope env = client.detail(InvanarEndpoints.MEDICATION_DETAIL, id.getIdPart());
            if (env.htmlBody().isEmpty()) {
                throw new ResourceNotFoundException(id);
            }
            MedicationDetail d = parser.parseMedicationDetail(env.htmlBody());
            return MedicationStatementMapper.fromDetail(d, id.getIdPart(), AuthContext.patientReference(req));
        } catch (IOException e) {
            throw new InternalErrorException("Upstream medication/detailview failed: " + e.getMessage(), e);
        }
    }

    @Search
    public Bundle search(@OptionalParam(name = "patient") ReferenceParam patient,
                         @Count Integer count,
                         @Offset Integer offset,
                         RequestDetails req) {
        return SkeletonProviderSupport.pollAndMap(
                InvanarEndpoints.MEDICATION_POLL, "MedicationStatement", count, offset, req,
                SkeletonMappers::medicationStatement);
    }
}
