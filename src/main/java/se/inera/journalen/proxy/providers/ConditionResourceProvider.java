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
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.IdType;

import java.io.IOException;

import se.inera.journalen.proxy.mapping.ConditionMapper;
import se.inera.journalen.proxy.mapping.PaginationUtil;
import se.inera.journalen.proxy.server.AuthContext;
import se.inera.journalen.proxy.upstream.FilterSpec;
import se.inera.journalen.proxy.upstream.InvanarClient;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;
import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.DiagnosisDetail;
import se.inera.journalen.proxy.upstream.dto.PollEnvelope;

public class ConditionResourceProvider implements IResourceProvider {

    private final PartialViewParser parser = new PartialViewParser();

    @Override
    public Class<Condition> getResourceType() {
        return Condition.class;
    }

    @Read
    public Condition read(@IdParam IdType id, RequestDetails req) {
        InvanarClient client = AuthContext.client(req);
        try {
            PollEnvelope env = client.detail(InvanarEndpoints.DIAGNOSIS_DETAIL, id.getIdPart());
            if (env.htmlBody().isEmpty()) {
                throw new ResourceNotFoundException(id);
            }
            DiagnosisDetail d = parser.parseDiagnosisDetail(env.htmlBody());
            return ConditionMapper.fromDetail(d, id.getIdPart(), AuthContext.patientReference(req));
        } catch (IOException e) {
            throw new InternalErrorException("Upstream diagnosis detail failed: " + e.getMessage(), e);
        }
    }

    @Search
    public Bundle search(@OptionalParam(name = "patient") ReferenceParam patient,
                         @Count Integer count,
                         @Offset Integer offset,
                         RequestDetails req) {
        InvanarClient client = AuthContext.client(req);
        int take = PaginationUtil.clampCount(count);
        int skip = PaginationUtil.clampOffset(offset);
        FilterSpec fs = FilterSpec.of(skip, take);
        try {
            PollEnvelope env = client.poll(InvanarEndpoints.DIAGNOSIS_POLL, fs);
            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
            if (env.totalNumberOfRows != null) bundle.setTotal(env.totalNumberOfRows);
            String patientRef = AuthContext.patientReference(req);
            for (var row : parser.parseListRows(env.htmlBody())) {
                Condition c = ConditionMapper.fromListRow(row, patientRef);
                bundle.addEntry().setFullUrl(req.getFhirServerBase() + "/Condition/" + c.getIdElement().getIdPart())
                        .setResource(c);
            }
            PaginationUtil.addNextLink(bundle, req, "Condition", skip, take, env.totalNumberOfRows);
            return bundle;
        } catch (IOException e) {
            throw new InternalErrorException("Upstream diagnosis poll failed: " + e.getMessage(), e);
        }
    }
}
