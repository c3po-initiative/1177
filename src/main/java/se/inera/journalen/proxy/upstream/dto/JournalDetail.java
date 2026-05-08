package se.inera.journalen.proxy.upstream.dto;

/**
 * Common fields parsed out of any {@code /journalcategories/&lt;cat&gt;/detailview}
 * response. Every category's detailview shares this shell:
 * <ul>
 *   <li>{@code h3.nc-heading__information-type} — the document/note type</li>
 *   <li>{@code .nc-document-timestamp} — "YYYY-MM-DD HH:mm"</li>
 *   <li>"Antecknad av" row → name (role) + care unit</li>
 *   <li>{@code .ic-alert} containing "Osignerad ..." → unsigned flag</li>
 * </ul>
 * Type-specific subclasses (e.g. {@link DiagnosisDetail}) add their own fields on top.
 */
public class JournalDetail {
    public String title;          // e.g. "Inskrivning", "Sammanfattning", "Diagnos: <name>"
    public String timestamp;      // "YYYY-MM-DD HH:mm"
    public String asserterName;
    public String asserterRole;
    public String careUnit;
    public boolean signed = true; // false when "Osignerad <X>" alert is present
    public String html;           // raw PartialView, for narrative
}
