package se.inera.journalen.proxy.mapping;

import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;

import java.util.Date;
import java.util.Locale;

import se.inera.journalen.proxy.upstream.dto.ServiceRequestDetail;

/**
 * Deep mapping for {@link ServiceRequest} from the parsed referral detail.
 *
 * Status timeline is preserved as ordered notes ({@code Annotation}); the latest entry's
 * Swedish status text is mapped onto {@code ServiceRequest.status} where it has a clear
 * FHIR equivalent.
 */
public final class ServiceRequestMapper {

    private ServiceRequestMapper() {}

    public static ServiceRequest fromDetail(ServiceRequestDetail d, String id, String patientReference) {
        ServiceRequest sr = new ServiceRequest();
        sr.setId(id);
        sr.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
        sr.setSubject(new Reference(patientReference));

        // Type/code from the heading ("Remiss")
        if (d.title != null) sr.setCode(new CodeableConcept().setText(d.title));

        // Authored on — from timestamp
        if (d.timestamp != null) {
            Date when = DateUtil.parseDateTime(d.timestamp);
            if (when != null) sr.setAuthoredOn(when);
        }

        // Sender (requester)
        if (d.sender != null) {
            sr.setRequester(new Reference().setDisplay(d.sender));
        } else if (d.asserterName != null) {
            sr.setRequester(new Reference().setDisplay(d.asserterName));
        }

        // Status timeline → notes; latest entry drives the status field
        ServiceRequest.ServiceRequestStatus latestStatus = ServiceRequest.ServiceRequestStatus.ACTIVE;
        ServiceRequestDetail.StatusEntry latest = null;
        for (ServiceRequestDetail.StatusEntry e : d.statusTimeline) {
            String txt = (e.date != null ? e.date + " " : "")
                    + (e.status != null ? e.status : "")
                    + (e.byUnit != null && !e.byUnit.isEmpty() ? " — " + e.byUnit : "");
            Annotation note = new Annotation().setText(txt);
            if (e.date != null) {
                Date when = DateUtil.parseDate(e.date);
                if (when != null) note.setTime(when);
            }
            sr.addNote(note);
            latest = e;
        }
        if (latest != null && latest.status != null) {
            latestStatus = mapStatus(latest.status);
        }
        sr.setStatus(latestStatus);

        if (d.html != null) {
            Narrative n = new Narrative();
            n.setStatus(Narrative.NarrativeStatus.GENERATED);
            n.setDivAsString(NarrativeUtil.wrap(d.html));
            sr.setText(n);
        }
        return sr;
    }

    private static ServiceRequest.ServiceRequestStatus mapStatus(String swedish) {
        if (swedish == null) return ServiceRequest.ServiceRequestStatus.ACTIVE;
        String t = swedish.toLowerCase(Locale.ROOT);
        if (t.contains("accepter")) return ServiceRequest.ServiceRequestStatus.ACTIVE;
        if (t.contains("klar") || t.contains("avslut") || t.contains("slutf"))
            return ServiceRequest.ServiceRequestStatus.COMPLETED;
        if (t.contains("avbry") || t.contains("inställ"))
            return ServiceRequest.ServiceRequestStatus.REVOKED;
        if (t.contains("åter") || t.contains("retur"))
            return ServiceRequest.ServiceRequestStatus.ENTEREDINERROR;
        if (t.contains("inkomm") || t.contains("regist"))
            return ServiceRequest.ServiceRequestStatus.ACTIVE;
        return ServiceRequest.ServiceRequestStatus.ACTIVE;
    }
}
