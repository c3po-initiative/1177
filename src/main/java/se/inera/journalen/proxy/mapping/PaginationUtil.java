package se.inera.journalen.proxy.mapping;

import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.r4.model.Bundle;

public final class PaginationUtil {

    public static final int DEFAULT_COUNT = 50;
    public static final int MAX_COUNT = 200;

    private PaginationUtil() {}

    public static int clampCount(Integer count) {
        if (count == null || count <= 0) return DEFAULT_COUNT;
        return Math.min(count, MAX_COUNT);
    }

    public static int clampOffset(Integer offset) {
        return offset == null || offset < 0 ? 0 : offset;
    }

    /**
     * Adds a {@code link[rel=next]} to the bundle if there are more results upstream.
     * The base URL is taken from the FHIR request so the link round-trips cleanly.
     */
    public static void addNextLink(Bundle bundle, RequestDetails req,
                                   String resourceType, int offset, int count, Integer total) {
        if (total == null || offset + count >= total) return;
        String base = req.getFhirServerBase();
        if (base == null) return;
        String url = base + "/" + resourceType + "?_offset=" + (offset + count) + "&_count=" + count;
        bundle.addLink().setRelation("next").setUrl(url);
    }
}
