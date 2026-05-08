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
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;

import java.io.IOException;
import java.util.Date;

import se.inera.journalen.proxy.mapping.DateUtil;
import se.inera.journalen.proxy.mapping.NarrativeUtil;
import se.inera.journalen.proxy.mapping.PaginationUtil;
import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.server.AuthContext;
import se.inera.journalen.proxy.upstream.FilterSpec;
import se.inera.journalen.proxy.upstream.InvanarClient;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;
import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.JournalDetail;
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

    /**
     * Deep read: pulls visit type, date+time, and responsible practitioner from the
     * journal-overview detailview HTML.
     */
    @Read
    public Encounter read(@IdParam IdType id, RequestDetails req) {
        InvanarClient client = AuthContext.client(req);
        try {
            PollEnvelope env = client.detail(InvanarEndpoints.JOURNAL_DETAIL, id.getIdPart());
            if (env.htmlBody().isEmpty()) {
                throw new ResourceNotFoundException(id);
            }
            JournalDetail d = new PartialViewParser().parseJournalDetail(env.htmlBody());

            Encounter e = new Encounter();
            e.setId(id.getIdPart());
            e.setStatus(Encounter.EncounterStatus.FINISHED);
            e.setSubject(new Reference(AuthContext.patientReference(req)));
            if (d.title != null) {
                e.addType(new CodeableConcept().setText(d.title));
            }
            // Encounter.class from heading (Mottagningsbesök → AMB, Hembesök → HH, …)
            org.hl7.fhir.r4.model.Coding cls = encounterClass(d.title);
            if (cls != null) e.setClass_(cls);

            if (d.timestamp != null) {
                Date when = DateUtil.parseDateTime(d.timestamp);
                if (when != null) e.setPeriod(new Period().setStart(when));
            }
            if (d.asserterName != null) {
                String display = d.asserterRole != null
                        ? d.asserterName + " (" + d.asserterRole + ")"
                        : d.asserterName;
                e.addParticipant().setIndividual(new Reference().setDisplay(display));
            }
            if (d.careUnit != null) {
                e.setServiceProvider(new Reference().setDisplay(d.careUnit));
            }
            if (d.html != null) {
                Narrative n = new Narrative();
                n.setStatus(Narrative.NarrativeStatus.GENERATED);
                n.setDivAsString(NarrativeUtil.wrap(d.html));
                e.setText(n);
            }
            return e;
        } catch (IOException ex) {
            throw new InternalErrorException("Upstream journaloverview/detailview failed: " + ex.getMessage(), ex);
        }
    }

    private static org.hl7.fhir.r4.model.Coding encounterClass(String swedishType) {
        if (swedishType == null) return null;
        String t = swedishType.toLowerCase(java.util.Locale.ROOT);
        String code, display;
        if (t.contains("mottagningsbes")) { code = "AMB"; display = "ambulatory"; }
        else if (t.contains("hembes"))    { code = "HH";  display = "home health"; }
        else if (t.contains("distans"))   { code = "VR";  display = "virtual"; }
        else if (t.contains("akut"))      { code = "EMER"; display = "emergency"; }
        else if (t.contains("inskrivning") || t.contains("vårdtillfäll") || t.contains("vardtillfall"))
                                          { code = "IMP"; display = "inpatient encounter"; }
        else return null;
        return new org.hl7.fhir.r4.model.Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")
                .setCode(code).setDisplay(display);
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
