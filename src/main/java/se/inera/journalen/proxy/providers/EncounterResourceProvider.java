package se.inera.journalen.proxy.providers;

import ca.uhn.fhir.rest.annotation.Count;
import ca.uhn.fhir.rest.annotation.Offset;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;

import java.io.IOException;

import se.inera.journalen.proxy.mapping.PaginationUtil;
import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.server.AuthContext;
import se.inera.journalen.proxy.upstream.FilterSpec;
import se.inera.journalen.proxy.upstream.InvanarClient;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;
import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.PollEnvelope;

/**
 * Care contacts (Vårdkontakter) are not exposed as a dedicated category endpoint upstream;
 * they live as item-type {@code "CareContact"} rows inside the journal-overview timeline.
 * This provider polls the timeline and filters the parsed rows by item type.
 */
public class EncounterResourceProvider implements IResourceProvider {

    private static final String ITEM_TYPE_ATTR = "data-cy-journal-overview-item-type=\"CareContact\"";

    @Override
    public Class<Encounter> getResourceType() {
        return Encounter.class;
    }

    @Search
    public Bundle search(@OptionalParam(name = "patient") ReferenceParam patient,
                         @Count Integer count,
                         @Offset Integer offset,
                         RequestDetails req) {
        InvanarClient client = AuthContext.client(req);
        PartialViewParser parser = new PartialViewParser();
        int take = PaginationUtil.clampCount(count);
        int skip = PaginationUtil.clampOffset(offset);
        try {
            PollEnvelope env = client.poll(InvanarEndpoints.JOURNAL_TIMELINE, FilterSpec.of(skip, take));
            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
            String patientRef = AuthContext.patientReference(req);
            int matched = 0;
            for (var row : parser.parseListRows(env.htmlBody())) {
                if (row.html == null || !row.html.contains(ITEM_TYPE_ATTR)) continue;
                Encounter e = SkeletonMappers.encounter(row, patientRef);
                bundle.addEntry()
                        .setFullUrl(req.getFhirServerBase() + "/Encounter/" + e.getIdElement().getIdPart())
                        .setResource(e);
                matched++;
            }
            // We can't know the upstream's true CareContact total without paging; report
            // what we matched in this page.
            bundle.setTotal(matched);
            PaginationUtil.addNextLink(bundle, req, "Encounter", skip, take, env.totalNumberOfRows);
            return bundle;
        } catch (IOException e) {
            throw new InternalErrorException("Upstream timeline poll failed: " + e.getMessage(), e);
        }
    }
}
