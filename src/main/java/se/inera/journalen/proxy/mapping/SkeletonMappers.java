package se.inera.journalen.proxy.mapping;

import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Narrative;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.hl7.fhir.r4.model.ServiceRequest;

import se.inera.journalen.proxy.upstream.PartialViewParser;
import se.inera.journalen.proxy.upstream.dto.ListRow;

import java.util.Date;

/**
 * Skeleton resource builders for the categories where we don't (yet) parse the
 * Swedish-language detail page. Every resource gets:
 * <ul>
 *   <li>id from data-id</li>
 *   <li>subject/patient reference</li>
 *   <li>a date parsed from data-date / data-cy-datetime</li>
 *   <li>narrative text.div = sanitized row html</li>
 * </ul>
 */
public final class SkeletonMappers {

    private SkeletonMappers() {}

    public static ServiceRequest serviceRequest(ListRow row, String patient) {
        ServiceRequest r = new ServiceRequest();
        r.setId(row.id);
        r.setStatus(ServiceRequest.ServiceRequestStatus.ACTIVE);
        r.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
        r.setSubject(new Reference(patient));
        Date d = bestDate(row);
        if (d != null) r.setAuthoredOn(d);
        if (row.ariaLabel != null) {
            r.setCode(new CodeableConcept().setText(row.ariaLabel));
        }
        applyNarrative(r, row);
        return r;
    }

    public static CarePlan carePlan(ListRow row, String patient) {
        CarePlan p = new CarePlan();
        p.setId(row.id);
        p.setStatus(CarePlan.CarePlanStatus.ACTIVE);
        p.setIntent(CarePlan.CarePlanIntent.PLAN);
        p.setSubject(new Reference(patient));
        Date d = bestDate(row);
        if (d != null) p.setCreated(d);
        if (row.ariaLabel != null) p.setTitle(row.ariaLabel);
        applyNarrative(p, row);
        return p;
    }

    public static Immunization immunization(ListRow row, String patient) {
        Immunization im = new Immunization();
        im.setId(row.id);
        im.setStatus(Immunization.ImmunizationStatus.COMPLETED);
        im.setPatient(new Reference(patient));
        Date d = bestDate(row);
        if (d != null) im.setOccurrence(new DateTimeType(d));
        if (row.ariaLabel != null) {
            im.setVaccineCode(new CodeableConcept().setText(row.ariaLabel));
        }
        applyNarrative(im, row);
        return im;
    }

    public static Observation growth(ListRow row, String patient) {
        Observation o = baseObservation(row, patient);
        o.addCategory(new CodeableConcept().addCoding(new org.hl7.fhir.r4.model.Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
                .setCode("vital-signs").setDisplay("Vital Signs")));
        return o;
    }

    public static Observation laboratory(ListRow row, String patient) {
        Observation o = baseObservation(row, patient);
        o.addCategory(new CodeableConcept().addCoding(new org.hl7.fhir.r4.model.Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
                .setCode("laboratory").setDisplay("Laboratory")));
        return o;
    }

    public static DiagnosticReport diagnosticReport(ListRow row, String patient) {
        DiagnosticReport r = new DiagnosticReport();
        r.setId(row.id);
        r.setStatus(DiagnosticReport.DiagnosticReportStatus.FINAL);
        r.setSubject(new Reference(patient));
        Date d = bestDate(row);
        if (d != null) r.setEffective(new DateTimeType(d));
        if (row.ariaLabel != null) {
            r.setCode(new CodeableConcept().setText(row.ariaLabel));
        }
        applyNarrative(r, row);
        return r;
    }

    public static DocumentReference documentReference(ListRow row, String patient) {
        DocumentReference d = new DocumentReference();
        d.setId(row.id);
        d.setStatus(org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus.CURRENT);
        d.setSubject(new Reference(patient));
        Date date = bestDate(row);
        if (date != null) d.setDate(date);
        if (row.ariaLabel != null) d.setDescription(row.ariaLabel);
        applyNarrative(d, row);
        return d;
    }

    public static AllergyIntolerance allergyIntolerance(ListRow row, String patient) {
        AllergyIntolerance a = new AllergyIntolerance();
        a.setId(row.id);
        a.setPatient(new Reference(patient));
        a.setClinicalStatus(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical")
                .setCode("active").setDisplay("Active")));
        a.setVerificationStatus(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/allergyintolerance-verification")
                .setCode("unconfirmed").setDisplay("Unconfirmed")));
        Date d = bestDate(row);
        if (d != null) a.setRecordedDate(d);
        if (row.ariaLabel != null) {
            // aria-label is "Datum YYYY-MM-DD, orsak <reason>, vårdenhet <unit>, <region>, <status>"
            a.setCode(new CodeableConcept().setText(row.ariaLabel));
        }
        applyNarrative(a, row);
        return a;
    }

    public static MedicationStatement medicationStatement(ListRow row, String patient) {
        MedicationStatement m = new MedicationStatement();
        m.setId(row.id);
        m.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
        m.setSubject(new Reference(patient));
        Date d = bestDate(row);
        if (d != null) m.setDateAsserted(d);
        if (row.ariaLabel != null) {
            m.setMedication(new CodeableConcept().setText(row.ariaLabel));
        }
        applyNarrative(m, row);
        return m;
    }

    /** Clinical note (CareDocumentation row) — distinct from journal-overview rows. */
    public static DocumentReference clinicalNote(ListRow row, String patient) {
        DocumentReference d = documentReference(row, patient);
        d.addCategory(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://hl7.org/fhir/us/core/CodeSystem/us-core-documentreference-category")
                .setCode("clinical-note").setDisplay("Clinical Note")));
        return d;
    }

    /**
     * Deep-mapped DocumentReference (clinical note) built from a parsed detailview.
     * Pulls type, date, author, and signed status out of the JournalDetail shell.
     */
    public static DocumentReference clinicalNoteFromDetail(
            se.inera.journalen.proxy.upstream.dto.JournalDetail detail,
            String id, String patient) {
        DocumentReference d = new DocumentReference();
        d.setId(id);
        d.setStatus(org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus.CURRENT);
        // CareDocumentation rows are signed unless flagged "Osignerad" — map to docStatus.
        d.setDocStatus(detail.signed
                ? DocumentReference.ReferredDocumentStatus.FINAL
                : DocumentReference.ReferredDocumentStatus.PRELIMINARY);
        d.setSubject(new Reference(patient));
        if (detail.timestamp != null) {
            Date when = DateUtil.parseDateTime(detail.timestamp);
            if (when != null) d.setDate(when);
        }
        if (detail.title != null) {
            d.setType(new CodeableConcept().setText(detail.title));
        }
        d.addCategory(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://hl7.org/fhir/us/core/CodeSystem/us-core-documentreference-category")
                .setCode("clinical-note").setDisplay("Clinical Note")));
        if (detail.asserterName != null) {
            String display = detail.asserterRole != null
                    ? detail.asserterName + " (" + detail.asserterRole + ")"
                    : detail.asserterName;
            d.addAuthor(new Reference().setDisplay(display));
        }
        if (detail.careUnit != null) {
            d.setCustodian(new Reference().setDisplay(detail.careUnit));
        }
        if (detail.html != null) {
            org.hl7.fhir.r4.model.Narrative n = new org.hl7.fhir.r4.model.Narrative();
            n.setStatus(org.hl7.fhir.r4.model.Narrative.NarrativeStatus.GENERATED);
            n.setDivAsString(NarrativeUtil.wrap(detail.html));
            d.setText(n);
        }
        return d;
    }

    public static Observation functionalStatus(ListRow row, String patient) {
        Observation o = baseObservation(row, patient);
        o.addCategory(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
                .setCode("survey").setDisplay("Survey")));
        return o;
    }

    public static Encounter encounter(ListRow row, String patient) {
        Encounter e = new Encounter();
        e.setId(row.id);
        e.setStatus(Encounter.EncounterStatus.FINISHED);
        e.setSubject(new Reference(patient));
        Date d = bestDate(row);
        if (d != null) {
            // Single-instant period; the upstream UI shows date+time but no end.
            e.setPeriod(new Period().setStart(d));
        }
        if (row.ariaLabel != null) {
            e.addType(new CodeableConcept().setText(row.ariaLabel));
        }
        applyNarrative(e, row);
        return e;
    }

    /**
     * AuditEvent for an access-log row from {@code /LogsAndShare/JournalLog/PollUserAccessLogs}
     * (patient's own logins) or {@code /PollJournalLogs} (clinician access).
     * The row's {@code data-date} is "YYYY-MM-DD HH:mm" (datetime, not just date).
     */
    public static AuditEvent auditEvent(ListRow row, String patient) {
        AuditEvent a = new AuditEvent();
        a.setId(row.id);
        // Audit type for "access to a Patient resource" — closest standard code.
        a.setType(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/audit-event-type")
                .setCode("rest").setDisplay("RESTful Operation"));
        a.addSubtype(new Coding()
                .setSystem("http://hl7.org/fhir/restful-interaction")
                .setCode("read").setDisplay("read"));
        a.setAction(AuditEvent.AuditEventAction.R);
        Date d = bestDate(row);
        if (d != null) a.setRecordedElement(new InstantType(d));
        a.setOutcome(AuditEvent.AuditEventOutcome._0); // Success

        // Agent: who accessed the journal. data-date timestamp + AccessedBy text.
        AuditEvent.AuditEventAgentComponent agent = a.addAgent();
        agent.setRequestor(true);
        if (row.authorName != null) {
            agent.setName(row.authorName);
            agent.setWho(new Reference().setDisplay(row.authorName));
        }

        // Source: the journalen system.
        a.getSource().setObserver(new Reference().setDisplay("1177 Journalen"));

        // Entity: the patient's journal. action=R (read) on the patient resource.
        AuditEvent.AuditEventEntityComponent entity = a.addEntity();
        entity.setWhat(new Reference(patient));
        entity.getType().setSystem("http://terminology.hl7.org/CodeSystem/audit-entity-type")
                .setCode("1").setDisplay("Person");
        entity.getRole().setSystem("http://terminology.hl7.org/CodeSystem/object-role")
                .setCode("1").setDisplay("Patient");

        applyNarrative(a, row);
        return a;
    }

    /**
     * Consent for a privacy-block ("spärr") row from {@code /LogsAndShare/JournalBlock/Poll}.
     * Per Inera's UI, blocks are scoped to a care unit + provider and prevent that party from
     * reading the patient's journal. Maps to a "deny" Consent for category "info".
     */
    public static Consent consent(ListRow row, String patient) {
        Consent c = new Consent();
        c.setId(row.id);
        c.setStatus(Consent.ConsentState.ACTIVE);
        c.setScope(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/consentscope")
                .setCode("patient-privacy").setDisplay("Privacy Consent")));
        c.addCategory(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://loinc.org")
                .setCode("59284-0").setDisplay("Patient Consent")));
        c.setPatient(new Reference(patient));
        Date d = bestDate(row);
        if (d != null) c.setDateTimeElement(new org.hl7.fhir.r4.model.DateTimeType(d));
        c.setProvision(new Consent.ProvisionComponent()
                .setType(Consent.ConsentProvisionType.DENY));
        if (row.ariaLabel != null) {
            c.getProvision().addAction(new CodeableConcept().setText(row.ariaLabel));
        }
        applyNarrative(c, row);
        return c;
    }

    public static RelatedPerson relatedPerson(ListRow row, String patient) {
        RelatedPerson rp = new RelatedPerson();
        rp.setId(row.id != null ? row.id : "legal-rep");
        rp.setPatient(new Reference(patient));
        if (row.authorName != null) rp.addName().setText(row.authorName);
        applyNarrative(rp, row);
        return rp;
    }

    private static Observation baseObservation(ListRow row, String patient) {
        Observation o = new Observation();
        o.setId(row.id);
        o.setStatus(Observation.ObservationStatus.FINAL);
        o.setSubject(new Reference(patient));
        Date d = bestDate(row);
        if (d != null) o.setEffective(new DateTimeType(d));
        String label = PartialViewParser.diagnosisFromAriaLabel(row.ariaLabel);
        String text = label != null ? label : row.ariaLabel;
        if (text != null) {
            o.setCode(new CodeableConcept().setText(text));
        }
        applyNarrative(o, row);
        return o;
    }

    private static Date bestDate(ListRow row) {
        if (row.dateTime != null) {
            Date d = DateUtil.parseDateTime(row.dateTime);
            if (d != null) return d;
        }
        if (row.date != null && row.date.contains(" ")) {
            // Some endpoints (access logs) put a full datetime in data-date.
            Date d = DateUtil.parseDateTime(row.date);
            if (d != null) return d;
        }
        return DateUtil.parseDate(row.date);
    }

    private static void applyNarrative(org.hl7.fhir.r4.model.DomainResource r, ListRow row) {
        if (row.html == null) return;
        Narrative n = new Narrative();
        n.setStatus(Narrative.NarrativeStatus.GENERATED);
        n.setDivAsString(NarrativeUtil.wrap(row.html));
        r.setText(n);
    }
}
