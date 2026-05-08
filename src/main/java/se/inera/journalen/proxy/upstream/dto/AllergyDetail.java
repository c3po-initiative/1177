package se.inera.journalen.proxy.upstream.dto;

/**
 * Allergy-specific extras on top of the shared {@link JournalDetail} shell.
 * Source: {@code /journalcategories/attentionSignals/detailview}.
 *
 * The detail page's {@code h3.nc-heading__information-type} carries the reaction type
 * (e.g. {@code Överkänslighet} = "hypersensitivity"), and an inline row of the same name
 * carries the actual allergen text (e.g. {@code Fisk}). Severity, certainty, validity
 * window, and signed-by are exposed as additional rows.
 */
public class AllergyDetail extends JournalDetail {
    /** "Överkänslighet" row → the allergen, e.g. {@code "Fisk"}. */
    public String allergen;
    /** "Allvarlighetsgrad" — severity, e.g. {@code "Besvärande"}, {@code "Allvarlig"}. */
    public String severity;
    /** "Visshetsgrad" — certainty, e.g. {@code "Bekräftad"}. */
    public String certainty;
    /** "Giltighetstid" — validity window, e.g. {@code "Från 2023-03-02"}. */
    public String validityRaw;
    /** "Aktuell" — active flag, {@code "Ja"} / {@code "Nej"}. */
    public String activeRaw;
    /** "Signerad" — signed-by line, e.g. {@code "2023-03-02, Åke Lövenhed"}. */
    public String signedRaw;
}
