package se.inera.journalen.proxy.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Reference;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * Maps a single bokadetider appointment JSON object to FHIR {@link Appointment}.
 *
 * Source shape (1177 booking service):
 * <pre>{@code
 * {
 *   "appointmentHsaId": "TSE2321000057-5SXK",
 *   "appointmentId": "a5d24832-3136-3e4a-...",
 *   "facilityName": "Blodcentralen Höglandssjukhuset Eksjö",
 *   "facilityDetails": {
 *     "externalLink": "https://www.1177.se/Hitta-vard/Kontakt/?hsaid=TSE2321000057-5SXK",
 *     "facilityService": { "hsaId": "...", "cancelingAvailable": true, ... }
 *   },
 *   "startTime": "2026-05-12T07:30:00",
 *   "timeTypeName": "Blodgivning Eksjö",
 *   "title": "Tisdag 12 maj 2026 kl. 07.30"
 * }
 * }</pre>
 */
public final class AppointmentMapper {

    private static final String HSA_SYSTEM = "urn:oid:1.2.752.29.4.19";   // Swedish HSA-ID OID
    private static final String FACILITY_SERVICE_HSA = "urn:oid:1.2.752.29.4.19";

    private AppointmentMapper() {}

    public static Appointment fromJson(JsonNode node, String patientReference) {
        Appointment a = new Appointment();

        String id = node.path("appointmentId").asText(null);
        if (id != null && !id.isEmpty()) a.setId(id);

        String hsaId = node.path("appointmentHsaId").asText(null);
        if (hsaId != null && !hsaId.isEmpty()) {
            a.addIdentifier(new Identifier().setSystem(HSA_SYSTEM).setValue(hsaId));
        }

        a.setStatus(Appointment.AppointmentStatus.BOOKED);

        String startStr = node.path("startTime").asText(null);
        if (startStr != null && !startStr.isEmpty()) {
            Date when = parseLocalDateTime(startStr);
            if (when != null) a.setStart(when);
        }

        String timeType = node.path("timeTypeName").asText(null);
        if (timeType != null && !timeType.isEmpty()) {
            a.addServiceType(new CodeableConcept().setText(timeType));
        }

        String title = node.path("title").asText(null);
        if (title != null && !title.isEmpty()) {
            a.setDescription(title);
        }

        // Patient participant
        Appointment.AppointmentParticipantComponent patientPart = a.addParticipant();
        patientPart.setActor(new Reference(patientReference));
        patientPart.addType(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/v3-ParticipationType")
                .setCode("SBJ").setDisplay("subject")));
        patientPart.setRequired(Appointment.ParticipantRequired.REQUIRED);
        patientPart.setStatus(Appointment.ParticipationStatus.ACCEPTED);

        // Location participant — populate display from facilityName, identifier from facility HSA
        String facilityName = node.path("facilityName").asText(null);
        String facilityHsa = node.path("facilityDetails").path("facilityService").path("hsaId").asText(null);
        if (facilityName != null || facilityHsa != null) {
            Appointment.AppointmentParticipantComponent loc = a.addParticipant();
            Reference locRef = new Reference();
            if (facilityName != null) locRef.setDisplay(facilityName);
            if (facilityHsa != null) {
                locRef.setIdentifier(new Identifier().setSystem(FACILITY_SERVICE_HSA).setValue(facilityHsa));
            }
            loc.setActor(locRef);
            loc.addType(new CodeableConcept().addCoding(new Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v3-ParticipationType")
                    .setCode("LOC").setDisplay("location")));
            loc.setRequired(Appointment.ParticipantRequired.REQUIRED);
            loc.setStatus(Appointment.ParticipationStatus.ACCEPTED);
        }

        // Narrative — keep the original title as a tiny div. We avoid stuffing the JSON in.
        Narrative n = new Narrative();
        n.setStatus(Narrative.NarrativeStatus.GENERATED);
        String div = "<div xmlns=\"http://www.w3.org/1999/xhtml\">" +
                escape(title != null ? title : (timeType != null ? timeType : "Appointment")) +
                "</div>";
        n.setDivAsString(div);
        a.setText(n);

        return a;
    }

    private static Date parseLocalDateTime(String s) {
        try {
            // Source format is local time without zone: "2026-05-12T07:30:00"
            LocalDateTime ldt = LocalDateTime.parse(s);
            return Date.from(ldt.atZone(ZoneId.of("Europe/Stockholm")).toInstant());
        } catch (Exception e) {
            return null;
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
