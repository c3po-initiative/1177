package se.inera.journalen.proxy.upstream.dto;

/**
 * Medication-specific extras on top of the shared {@link JournalDetail} shell.
 * Source: {@code /journalcategories/medication/detailview} HTML, which contains three
 * collapsible panels — top fields, "Dosering", "Ordination", and "Ordinationen registrerad av".
 */
public class MedicationDetail extends JournalDetail {

    /** Drug name from the heading (e.g. {@code "Matrifen"}, {@code "Eliquis"}). */
    public String medicationName;

    /** "Form och styrka" — e.g. {@code "Depotplåster 25 mikrog/timme"}, {@code "Tablett 125 mikrog"}. */
    public String formAndStrength;

    // --- Dosing panel ---
    /** "Doseringsanvisning" — full dosing instruction text. */
    public String dosageInstruction;
    /** "Behandlingstid" — treatment duration; often "Saknas" (missing). */
    public String treatmentDuration;

    // --- Prescription (Ordination) panel ---
    /** "Ordinationstidpunkt" — when the prescription was made. */
    public String prescriptionTime;
    /** "Behandlingsperiod" start — first segment of "YYYY-MM-DD - <end>". */
    public String treatmentPeriodStart;
    /** "Behandlingsperiod" end — null/empty if open-ended. */
    public String treatmentPeriodEnd;
    /** "Ordinationsorsak" — reason for the prescription (e.g. {@code "Mot smärta"}). */
    public String prescriptionReason;
    /** ATC code from "Aktiv substans" line (e.g. {@code "N02AB03"}). */
    public String atcCode;
    /** Active substance name from "Aktiv substans" (e.g. {@code "Fentanyl"}). */
    public String activeSubstanceName;
    /** Swedish NPL product ID from "Produktnamn" (e.g. {@code "20040916000804"}). */
    public String nplProductId;
    /** Product display name from "Produktnamn". */
    public String productName;
    /** "Tänkt administrationssätt" — route (e.g. {@code "kutant"}, {@code "peroralt"}). */
    public String routeOfAdministration;

    // --- Registered by panel ---
    /** "Namn" under "Ordinationen registrerad av". */
    public String registeredByName;
    /** "Vårdenhet" under "Ordinationen registrerad av" — usually the technical care unit. */
    public String registeredByCareUnit;
}
