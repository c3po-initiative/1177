package se.inera.journalen.proxy.providers;

import ca.uhn.fhir.rest.annotation.Count;
import ca.uhn.fhir.rest.annotation.Offset;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Bundle;

import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;

/**
 * Surfaces the patient's journal access logs as FHIR AuditEvents.
 *
 * Default ({@code agent=patient}, the default): the patient's own logins to the
 * journal portal — every time the user opened their own journal.
 * {@code agent=clinician}: clinician/staff accesses to the patient's journal
 * (often empty for test users; the endpoint is {@code PollJournalLogs}).
 */
public class AuditEventResourceProvider implements IResourceProvider {

    @Override
    public Class<AuditEvent> getResourceType() {
        return AuditEvent.class;
    }

    @Search
    public Bundle search(@OptionalParam(name = "patient") ReferenceParam patient,
                         @OptionalParam(name = "agent") TokenParam agent,
                         @Count Integer count,
                         @Offset Integer offset,
                         RequestDetails req) {
        boolean clinician = agent != null && "clinician".equalsIgnoreCase(agent.getValue());
        String path = clinician
                ? InvanarEndpoints.JOURNAL_LOGS
                : InvanarEndpoints.USER_ACCESS_LOGS;
        return SkeletonProviderSupport.pollAndMap(path, "AuditEvent", count, offset, req,
                SkeletonMappers::auditEvent);
    }
}
