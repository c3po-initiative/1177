package se.inera.journalen.proxy.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;

import java.time.Instant;
import java.util.Date;

/**
 * Maps a single {@code e-tjanster.at.1177.se} inbox message JSON object to FHIR
 * {@link Communication}.
 *
 * Source shape (list and detail items have the same fields, but {@code messageText}
 * is null in the list view):
 * <pre>{@code
 * {
 *   "id": 1547053,
 *   "threadTitle": "Meddelande från blodcentralen",
 *   "threadLabel": "Information",
 *   "facilityName": "Blodcentralen Höglandssjukhuset Eksjö",
 *   "facilityHsaId": "...",                  // sometimes null
 *   "messageDate": "2026-05-01T09:55:49.186Z",
 *   "readStatus": "READ" | "UNREAD",
 *   "title": "...",                          // detail only
 *   "messageText": "<div>...HTML...</div>",   // detail only
 *   "hasAttachment": false,
 *   "messagesInThread": 14
 * }
 * }</pre>
 */
public final class CommunicationMapper {

    public static final String INERA_HSA_SYSTEM = "urn:oid:1.2.752.29.4.19";
    public static final String CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/communication-category";

    private CommunicationMapper() {}

    public static Communication fromJson(JsonNode node, String patientReference) {
        Communication c = new Communication();
        c.setId(node.path("id").asText());

        c.setStatus(Communication.CommunicationStatus.COMPLETED);

        // Category: use threadLabel as the display, generic "notification" code by default.
        String label = node.path("threadLabel").asText(null);
        c.addCategory(new CodeableConcept()
                .addCoding(new Coding().setSystem(CATEGORY_SYSTEM).setCode("notification").setDisplay("Notification"))
                .setText(label));

        c.setSubject(new Reference(patientReference));
        c.addRecipient(new Reference(patientReference));

        // Sender: facilityName + optional HSA-ID
        Reference sender = new Reference();
        String facility = node.path("facilityName").asText(null);
        String facilityHsa = node.path("facilityHsaId").asText(null);
        boolean any = false;
        if (facility != null && !facility.isEmpty()) { sender.setDisplay(facility); any = true; }
        if (facilityHsa != null && !facilityHsa.isEmpty()) {
            sender.setIdentifier(new org.hl7.fhir.r4.model.Identifier()
                    .setSystem(INERA_HSA_SYSTEM).setValue(facilityHsa));
            any = true;
        }
        if (any) c.setSender(sender);

        // Sent timestamp
        String sent = node.path("messageDate").asText(null);
        if (sent != null && !sent.isEmpty()) {
            try {
                c.setSent(Date.from(Instant.parse(sent)));
            } catch (Exception ignored) {}
        }

        // Mark as received if read
        if ("READ".equalsIgnoreCase(node.path("readStatus").asText(""))) {
            c.setReceived(c.getSent());
            c.addNote().setText("Read");
        } else if ("UNREAD".equalsIgnoreCase(node.path("readStatus").asText(""))) {
            c.addNote().setText("Unread");
        }

        // Topic = title (detail) or threadTitle (fallback)
        String title = node.path("title").asText(null);
        if (title == null || title.isEmpty()) title = node.path("threadTitle").asText(null);
        if (title != null && !title.isEmpty()) {
            c.setTopic(new CodeableConcept().setText(title));
        }

        // Payload: messageText (HTML) is the body. List view leaves it null; that's fine —
        // a Communication with empty payload still represents the notification.
        String body = node.path("messageText").asText(null);
        if (body != null && !body.isEmpty()) {
            c.addPayload().setContent(new StringType(body));
        }

        // Narrative
        Narrative n = new Narrative();
        n.setStatus(Narrative.NarrativeStatus.GENERATED);
        StringBuilder div = new StringBuilder("<div xmlns=\"http://www.w3.org/1999/xhtml\">");
        div.append("<h3>").append(escape(title != null ? title : "Message")).append("</h3>");
        if (facility != null) div.append("<p>From: ").append(escape(facility)).append("</p>");
        if (sent != null) div.append("<p>Sent: ").append(escape(sent)).append("</p>");
        if (body != null) div.append(NarrativeUtil.wrap(body)
                .replaceFirst("<div xmlns=\"http://www\\.w3\\.org/1999/xhtml\">", "")
                .replaceAll("</div>$", ""));
        div.append("</div>");
        n.setDivAsString(div.toString());
        c.setText(n);

        return c;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
