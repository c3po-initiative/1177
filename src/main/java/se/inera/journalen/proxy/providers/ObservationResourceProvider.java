package se.inera.journalen.proxy.providers;

import ca.uhn.fhir.rest.annotation.Count;
import ca.uhn.fhir.rest.annotation.Offset;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;

import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;

/**
 * Observation surfaces both growth observations and laboratory results.
 * The {@code category} search param picks which upstream poll to make.
 * Default: laboratory.
 */
public class ObservationResourceProvider implements IResourceProvider {

    @Override
    public Class<Observation> getResourceType() {
        return Observation.class;
    }

    @Search
    public Bundle search(@OptionalParam(name = "patient") ReferenceParam patient,
                         @OptionalParam(name = "category") TokenParam category,
                         @Count Integer count,
                         @Offset Integer offset,
                         RequestDetails req) {
        String cat = category != null ? category.getValue() : null;
        if ("vital-signs".equalsIgnoreCase(cat)) {
            return SkeletonProviderSupport.pollAndMap(
                    InvanarEndpoints.GROWTH_POLL, "Observation", count, offset, req,
                    SkeletonMappers::growth, true);
        }
        if ("survey".equalsIgnoreCase(cat)) {
            return SkeletonProviderSupport.pollAndMap(
                    InvanarEndpoints.FUNCTIONAL_STATUS_POLL, "Observation", count, offset, req,
                    SkeletonMappers::functionalStatus);
        }
        // Default: laboratory
        return SkeletonProviderSupport.pollAndMap(
                InvanarEndpoints.LAB_POLL, "Observation", count, offset, req,
                SkeletonMappers::laboratory);
    }
}
