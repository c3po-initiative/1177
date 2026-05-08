package se.inera.journalen.proxy.providers;

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.IdType;

import java.time.LocalDate;
import java.util.List;

import se.inera.journalen.proxy.ips.PatientSummaryMapper;
import se.inera.journalen.proxy.ips.PatientSummaryService;
import se.inera.journalen.proxy.server.AuthContext;

/**
 * Synthesized Patient: the upstream API doesn't expose a patient bundle, only the
 * personnummer used to log in. We surface that under {@code Patient/me} (and
 * {@code Patient/&lt;personnummer&gt;}).
 */
public class PatientResourceProvider implements IResourceProvider {

    public static final String PNR_SYSTEM = "urn:oid:1.2.752.129.2.1.3.1";

    private final PatientSummaryService summaryService = new PatientSummaryService();

    @Override
    public Class<Patient> getResourceType() {
        return Patient.class;
    }

    @Read
    public Patient read(@IdParam IdType id, RequestDetails req) {
        return buildPatient(id.getIdPart(), req);
    }

    @Search
    public List<Patient> search(RequestDetails req) {
        return List.of(buildPatient("me", req));
    }

    /**
     * Patient/$summary — returns an IPS Document Bundle for the authenticated user.
     * Conforms to {@link http://hl7.org/fhir/uv/ips/}: type=document, Composition first,
     * LOINC-coded sections for problems / medications / allergies / immunizations / results.
     * The id parameter is currently ignored (the proxy serves only the authenticated patient).
     */
    @Operation(name = "$summary", idempotent = true, type = Patient.class)
    public Bundle summary(@IdParam IdType id, RequestDetails req) {
        var client = AuthContext.client(req);
        String identifier = AuthContext.identifier(req);
        String patientRef = AuthContext.patientReference(req);
        var data = summaryService.fetch(client, identifier, patientRef);
        return PatientSummaryMapper.toIpsBundle(data);
    }

    private Patient buildPatient(String idPart, RequestDetails req) {
        String pnr = AuthContext.identifier(req);
        Patient p = new Patient();
        p.setId(idPart);
        if (pnr != null) {
            p.addIdentifier(new Identifier().setSystem(PNR_SYSTEM).setValue(pnr));
            populateDemographicsFromPersonnummer(p, pnr);
        }
        return p;
    }

    /**
     * Swedish personnummer encodes both birth date and gender:
     * <ul>
     *   <li>positions 1-8: {@code YYYYMMDD}</li>
     *   <li>position 11 (3rd-from-right): odd digit = male, even = female</li>
     * </ul>
     * 10-digit short form ({@code YYMMDD-NNNC}) cannot disambiguate the century without the
     * separator (+/-) so we only handle the full 12-digit form here.
     */
    static void populateDemographicsFromPersonnummer(Patient p, String pnr) {
        if (pnr == null || pnr.length() != 12 || !pnr.chars().allMatch(Character::isDigit)) return;
        try {
            int year  = Integer.parseInt(pnr.substring(0, 4));
            int month = Integer.parseInt(pnr.substring(4, 6));
            int day   = Integer.parseInt(pnr.substring(6, 8));
            // "Samordningsnummer" (coordination numbers) add 60 to the day; treat them as the
            // intended birth date by subtracting 60. Day-of-month must be 1..31 either way.
            int realDay = day > 60 ? day - 60 : day;
            LocalDate birth = LocalDate.of(year, month, realDay);
            p.setBirthDate(java.util.Date.from(birth.atStartOfDay(
                    java.time.ZoneId.of("Europe/Stockholm")).toInstant()));
            int genderDigit = Character.digit(pnr.charAt(10), 10);
            p.setGender((genderDigit % 2 == 0)
                    ? Enumerations.AdministrativeGender.FEMALE
                    : Enumerations.AdministrativeGender.MALE);
        } catch (Exception ignored) {
            // Not a valid pnr; leave demographics unset.
        }
    }
}
