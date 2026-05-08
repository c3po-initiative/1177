package se.inera.journalen.proxy.providers;

import ca.uhn.fhir.rest.annotation.Count;
import ca.uhn.fhir.rest.annotation.Offset;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DiagnosticReport;

import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;

public class DiagnosticReportResourceProvider implements IResourceProvider {

    @Override
    public Class<DiagnosticReport> getResourceType() {
        return DiagnosticReport.class;
    }

    @Search
    public Bundle search(@OptionalParam(name = "patient") ReferenceParam patient,
                         @Count Integer count,
                         @Offset Integer offset,
                         RequestDetails req) {
        return SkeletonProviderSupport.pollAndMap(
                InvanarEndpoints.LAB_OVERVIEW, "DiagnosticReport", count, offset, req,
                SkeletonMappers::diagnosticReport);
    }
}
