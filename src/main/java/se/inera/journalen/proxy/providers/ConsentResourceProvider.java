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
import org.hl7.fhir.r4.model.Consent;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import se.inera.journalen.proxy.mapping.PaginationUtil;
import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.server.AuthContext;
import se.inera.journalen.proxy.upstream.InvanarClient;
import se.inera.journalen.proxy.upstream.InvanarEndpoints;
import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.PollEnvelope;

/**
 * Surfaces journal privacy blocks ("spärrar") as FHIR Consents (deny provision).
 * The block-poll endpoint requires a different OrderByEnum than category polls.
 */
public class ConsentResourceProvider implements IResourceProvider {

    @Override
    public Class<Consent> getResourceType() {
        return Consent.class;
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

        // Custom fs body: BlockCareUnitAndCareProvider order, ascending.
        Map<String, Object> fs = new LinkedHashMap<>();
        fs.put("Skip", skip);
        fs.put("Take", take);
        for (String f : new String[]{"AuthorName","Type","InformationType","CareUnit","VaccineName","VaccineDisease","MedicationName","OngoingTreatment","LoggedPersonName","LoggedPersonRole","LoggedPersonCareProvider"}) {
            fs.put(f, Collections.emptyList());
        }
        fs.put("OrderDirection", "Ascending");
        fs.put("OrderByEnum", "BlockCareUnitAndCareProvider");
        fs.put("FilterArrays", Collections.emptyMap());
        fs.put("GetFiltersView", false);

        try {
            PollEnvelope env = client.postJson(InvanarEndpoints.JOURNAL_BLOCKS, Map.of("fs", fs));
            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET);
            bundle.setTotal(env.totalNumberOfRows != null ? env.totalNumberOfRows : 0);
            String patientRef = AuthContext.patientReference(req);
            for (var row : parser.parseListRows(env.htmlBody())) {
                Consent c = SkeletonMappers.consent(row, patientRef);
                bundle.addEntry()
                        .setFullUrl(req.getFhirServerBase() + "/Consent/" + c.getIdElement().getIdPart())
                        .setResource(c);
            }
            return bundle;
        } catch (IOException e) {
            throw new InternalErrorException("Upstream JournalBlock poll failed: " + e.getMessage(), e);
        }
    }
}
