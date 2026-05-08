package se.inera.journalen.proxy.providers;

import ca.uhn.fhir.rest.annotation.Count;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Offset;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;

import java.io.IOException;

import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.server.AuthContext;
import se.inera.journalen.proxy.upstream.InvanarClient;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;
import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.JournalDetail;
import se.inera.journalen.proxy.upstream.dto.PollEnvelope;

public class DocumentReferenceResourceProvider implements IResourceProvider {

    private final PartialViewParser parser = new PartialViewParser();

    @Override
    public Class<DocumentReference> getResourceType() {
        return DocumentReference.class;
    }

    /**
     * Deep read for DocumentReference. Tries the careDocumentation detailview first
     * (since it's the richer source for clinical notes); falls back to the
     * journaloverview detailview which serves notes / referrals / care contacts via
     * the same shell HTML.
     */
    @Read
    public DocumentReference read(@IdParam IdType id, RequestDetails req) {
        InvanarClient client = AuthContext.client(req);
        String patientRef = AuthContext.patientReference(req);
        try {
            PollEnvelope env = client.detail(InvanarEndpoints.CARE_DOCUMENTATION_DETAIL, id.getIdPart());
            if (env.htmlBody().isEmpty()) {
                env = client.detail(InvanarEndpoints.JOURNAL_DETAIL, id.getIdPart());
            }
            if (env.htmlBody().isEmpty()) {
                throw new ResourceNotFoundException(id);
            }
            JournalDetail detail = parser.parseJournalDetail(env.htmlBody());
            return SkeletonMappers.clinicalNoteFromDetail(detail, id.getIdPart(), patientRef);
        } catch (IOException e) {
            throw new InternalErrorException("Upstream detailview failed: " + e.getMessage(), e);
        }
    }

    @Search
    public Bundle search(@OptionalParam(name = "patient") ReferenceParam patient,
                         @OptionalParam(name = "category") TokenParam category,
                         @Count Integer count,
                         @Offset Integer offset,
                         RequestDetails req) {
        // category=clinical-note → /journalcategories/careDocumentation/poll
        // (server-tagged with US Core "clinical-note" category in the mapper)
        if (category != null && "clinical-note".equalsIgnoreCase(category.getValue())) {
            return SkeletonProviderSupport.pollAndMap(
                    InvanarEndpoints.CARE_DOCUMENTATION_POLL, "DocumentReference", count, offset, req,
                    SkeletonMappers::clinicalNote);
        }
        // Default: full journal-overview timeline (everything the patient can see)
        return SkeletonProviderSupport.pollAndMap(
                InvanarEndpoints.JOURNAL_TIMELINE, "DocumentReference", count, offset, req,
                SkeletonMappers::documentReference);
    }
}
