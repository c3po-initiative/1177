package se.inera.journalen.proxy.providers;

import ca.uhn.fhir.rest.annotation.Count;
import ca.uhn.fhir.rest.annotation.Offset;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CarePlan;

import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;

public class CarePlanResourceProvider implements IResourceProvider {

    @Override
    public Class<CarePlan> getResourceType() {
        return CarePlan.class;
    }

    @Search
    public Bundle search(@OptionalParam(name = "patient") ReferenceParam patient,
                         @Count Integer count,
                         @Offset Integer offset,
                         RequestDetails req) {
        return SkeletonProviderSupport.pollAndMap(
                InvanarEndpoints.CAREPLAN_POLL, "CarePlan", count, offset, req,
                SkeletonMappers::carePlan);
    }
}
