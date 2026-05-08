package se.inera.journalen.proxy.providers;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DomainResource;

import java.io.IOException;
import java.util.function.BiFunction;

import se.inera.journalen.proxy.mapping.PaginationUtil;
import se.inera.journalen.proxy.server.AuthContext;
import se.inera.journalen.proxy.upstream.FilterSpec;
import se.inera.journalen.proxy.upstream.InvanarClient;
import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.ListRow;
import se.inera.journalen.proxy.upstream.dto.PollEnvelope;

public final class SkeletonProviderSupport {

    private SkeletonProviderSupport() {}

    /**
     * Standard search flow: poll the upstream endpoint, parse list rows, map each to a
     * FHIR resource, and assemble a searchset Bundle.
     */
    public static <R extends DomainResource> Bundle pollAndMap(
            String upstreamPath,
            String resourceType,
            Integer count,
            Integer offset,
            RequestDetails req,
            BiFunction<ListRow, String, R> mapper) {
        return pollAndMap(upstreamPath, resourceType, count, offset, req, mapper, false);
    }

    /**
     * @param skipOnly when true, send {@code fs} without {@code Take}/{@code GetFiltersView} —
     *                 required by growth/lab-overview/lab-cumulative endpoints whose schema lacks
     *                 those fields.
     */
    public static <R extends DomainResource> Bundle pollAndMap(
            String upstreamPath,
            String resourceType,
            Integer count,
            Integer offset,
            RequestDetails req,
            BiFunction<ListRow, String, R> mapper,
            boolean skipOnly) {
        InvanarClient client = AuthContext.client(req);
        PartialViewParser parser = new PartialViewParser();
        int take = PaginationUtil.clampCount(count);
        int skip = PaginationUtil.clampOffset(offset);
        try {
            FilterSpec fs = skipOnly ? FilterSpec.ofSkipOnly(skip) : FilterSpec.of(skip, take);
            PollEnvelope env = client.poll(upstreamPath, fs);
            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
            bundle.setTotal(env.totalNumberOfRows != null ? env.totalNumberOfRows : 0);
            String patientRef = AuthContext.patientReference(req);
            for (ListRow row : parser.parseListRows(env.htmlBody())) {
                R r = mapper.apply(row, patientRef);
                if (r == null) continue;
                bundle.addEntry()
                        .setFullUrl(req.getFhirServerBase() + "/" + resourceType + "/" + r.getIdElement().getIdPart())
                        .setResource(r);
            }
            PaginationUtil.addNextLink(bundle, req, resourceType, skip, take, env.totalNumberOfRows);
            return bundle;
        } catch (IOException e) {
            throw new InternalErrorException("Upstream " + upstreamPath + " failed: " + e.getMessage(), e);
        }
    }

}
