package se.inera.journalen.proxy.upstream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import se.inera.journalen.proxy.upstream.dto.DiagnosisDetail;
import se.inera.journalen.proxy.upstream.dto.JournalDetail;
import se.inera.journalen.proxy.upstream.dto.ListRow;
import se.inera.journalen.proxy.upstream.dto.MedicationDetail;

/**
 * Pure HTML extraction. The portal renders each row as
 * {@code <li class="nc-list-post">} with a {@code <button data-id data-date aria-label>}
 * child, and detail pages with discrete {@code .information-details__row} blocks.
 *
 * Selectors prefer {@code data-*} attributes (stable contract) over visual class names
 * where possible.
 */
public class PartialViewParser {

    /** Pattern that pulls "Name (Role)" out of a string like "Pernilla Rask (Läkare)". */
    private static final Pattern NAME_ROLE = Pattern.compile("^\\s*(.+?)\\s*\\(([^)]+)\\)\\s*$");

    /**
     * Pulls "diagnos &lt;name&gt;" out of an aria-label like
     * {@code "Datum 2017-07-19, diagnos Renovaskulär hypertoni, antecknad av ..."}.
     */
    private static final Pattern ARIA_DIAGNOSIS = Pattern.compile(", diagnos (.+?), antecknad av");

    public List<ListRow> parseListRows(String html) {
        List<ListRow> rows = new ArrayList<>();
        if (html == null || html.isBlank()) return rows;
        Document doc = parseFragment(html);

        // Several row shapes are in use across categories. The unifying invariant is
        // "an <li> with a data-id somewhere in its subtree". We collect by id to dedupe in case
        // the markup repeats the attribute on nested elements.
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Element li : doc.select("li:has([data-id]), li[data-id]")) {
            ListRow row = buildRow(li);
            if (row.id != null && !row.id.isBlank() && seen.add(row.id)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private ListRow buildRow(Element li) {
        ListRow row = new ListRow();
        Element src = firstNonNull(
                li.selectFirst("button[data-id]"),
                li.selectFirst("[data-id][data-date]"),
                li);
        row.id = src.attr("data-id");
        row.date = src.attr("data-date");
        row.dateTime = firstNonEmpty(src.attr("data-cy-datetime"), li.attr("data-cy-datetime"));
        row.ariaLabel = src.attr("aria-label");

        // Category list pages put the author in .AuthorName .ellipsis[title]; access-log rows
        // use .AccessedBy with a .nu-display-block child. We try both.
        Element author = li.selectFirst(".AuthorName .ellipsis[title]");
        if (author != null) {
            row.authorName = author.attr("title");
            var ellipses = li.select(".AuthorName .ellipsis[title]");
            if (ellipses.size() >= 2) row.careUnit = ellipses.get(1).attr("title");
        } else {
            Element accessed = li.selectFirst(".AccessedBy .nu-display-block");
            if (accessed != null) row.authorName = accessed.text().trim();
        }

        row.html = li.outerHtml();
        return row;
    }

    private static Element firstNonNull(Element... candidates) {
        for (Element e : candidates) if (e != null) return e;
        return null;
    }

    public DiagnosisDetail parseDiagnosisDetail(String html) {
        DiagnosisDetail d = new DiagnosisDetail();
        populateGeneric(d, html);
        if (d.html == null) return d;
        Document doc = parseFragment(html);

        d.headingName = stripDiagnosisPrefix(d.title);

        // Diagnosis-specific row: "Huvuddiagnos"
        for (Element row : doc.select(".information-details__row")) {
            Element title = row.selectFirst(".detail-title");
            Element desc  = row.selectFirst(".detail-description");
            if (title == null || desc == null) continue;
            if ("Huvuddiagnos".equals(title.text().trim())) {
                d.mainDiagnosis = desc.text().trim();
            }
        }
        if (d.mainDiagnosis == null || d.mainDiagnosis.isEmpty()) {
            d.mainDiagnosis = d.headingName;
        }
        return d;
    }

    /**
     * Generic detailview parser. Every category shares the same outer shell
     * (heading, timestamp, "Antecknad av" row, optional "Osignerad ..." alert);
     * type-specific extras (e.g. "Huvuddiagnos") are layered on top by
     * type-specific parser methods.
     */
    public JournalDetail parseJournalDetail(String html) {
        JournalDetail d = new JournalDetail();
        populateGeneric(d, html);
        return d;
    }

    /**
     * Medication detail parser. Pulls fields from all three expandable panels (Dosering,
     * Ordination, Ordinationen registrerad av), splits the combined codes from
     * "Produktnamn" and "Aktiv substans" rows, and parses the treatment period range.
     */
    public MedicationDetail parseMedicationDetail(String html) {
        MedicationDetail d = new MedicationDetail();
        populateGeneric(d, html);
        if (d.html == null) return d;
        d.medicationName = d.title;

        Document doc = parseFragment(html);
        for (Element row : doc.select(".information-details__row")) {
            Element titleEl = row.selectFirst(".detail-title");
            Element descEl = row.selectFirst(".detail-description");
            if (titleEl == null || descEl == null) continue;
            String title = titleEl.text().trim();
            // Drop screen-reader-only and aria-hidden alternatives that some rows duplicate
            // (e.g. "<span class='iu-sr-only'>Från X till saknas</span>
            //        <span aria-hidden='true'>X - -</span>").
            // We keep only what's visible to a sighted reader.
            org.jsoup.nodes.Element descCopy = descEl.clone();
            descCopy.select(".iu-sr-only").remove();
            String desc = descCopy.text().trim();
            // "Saknas" / "-" placeholder → treat as empty
            if ("-".equals(desc) || "Saknas".equals(desc) || desc.isEmpty()) continue;

            switch (title) {
                case "Form och styrka" -> {
                    if (d.formAndStrength == null) d.formAndStrength = desc;
                }
                case "Doseringsanvisning" -> d.dosageInstruction = desc;
                case "Behandlingstid" -> d.treatmentDuration = desc;
                case "Ordinationstidpunkt" -> d.prescriptionTime = desc;
                case "Behandlingsperiod" -> {
                    // Visible form is "YYYY-MM-DD - YYYY-MM-DD" or "YYYY-MM-DD - -" (open-ended).
                    // Split on " - " specifically so date-internal hyphens don't get hit.
                    String[] parts = desc.split(" - ", -1);
                    if (parts.length >= 1) d.treatmentPeriodStart = nullIfPlaceholder(parts[0].trim());
                    if (parts.length >= 2) d.treatmentPeriodEnd = nullIfPlaceholder(parts[1].trim());
                }
                case "Ordinationsorsak", "Behandlingsändamål" -> {
                    if (d.prescriptionReason == null) d.prescriptionReason = desc;
                }
                case "Aktiv substans" -> {
                    String[] parts = splitOnSlash(desc);
                    if (parts != null) {
                        d.atcCode = parts[0];
                        d.activeSubstanceName = parts[1];
                    } else {
                        d.activeSubstanceName = desc;
                    }
                }
                case "Produktnamn" -> {
                    // The desc may include the FASS link text ("Läs mer..."); only the first
                    // line is the "id / name" pair.
                    String firstLine = descEl.ownText().trim();
                    if (firstLine.isEmpty()) {
                        // ownText empty — try first text node
                        firstLine = desc.split("\n")[0].trim();
                    }
                    String[] parts = splitOnSlash(firstLine);
                    if (parts != null) {
                        d.nplProductId = parts[0];
                        d.productName = parts[1];
                    } else if (!firstLine.isEmpty()) {
                        d.productName = firstLine;
                    }
                }
                case "Tänkt administrationssätt" -> d.routeOfAdministration = desc;
                case "Namn" -> {
                    if (d.registeredByName == null) d.registeredByName = desc;
                }
                case "Vårdenhet" -> {
                    if (d.registeredByCareUnit == null) d.registeredByCareUnit = desc;
                }
                default -> {} // ignore other rows
            }
        }
        return d;
    }

    private static String[] splitOnSlash(String s) {
        if (s == null) return null;
        int idx = s.indexOf(" / ");
        if (idx < 0) return null;
        return new String[] { s.substring(0, idx).trim(), s.substring(idx + 3).trim() };
    }

    private static String nullIfPlaceholder(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() || "-".equals(t) || "Saknas".equals(t) ? null : t;
    }

    private void populateGeneric(JournalDetail d, String html) {
        if (html == null || html.isBlank()) return;
        Document doc = parseFragment(html);
        d.html = html;

        Element alertHeadline = doc.selectFirst(".ic-alert .ic-alert__headline");
        if (alertHeadline != null && alertHeadline.text().contains("Osignerad")) {
            d.signed = false;
        }

        Element h = doc.selectFirst("h3.nc-heading__information-type");
        if (h != null) d.title = h.text().trim();

        Element ts = doc.selectFirst(".nc-document-timestamp");
        if (ts != null) d.timestamp = ts.text().trim();

        for (Element row : doc.select(".information-details__row")) {
            Element title = row.selectFirst(".detail-title");
            Element desc  = row.selectFirst(".detail-description");
            if (title == null || desc == null) continue;
            if ("Antecknad av".equals(title.text().trim())) {
                Element block = desc.selectFirst("span.nu-display-block");
                if (block != null) d.careUnit = block.text().trim();
                String nameRole = desc.ownText().trim();
                Matcher m = NAME_ROLE.matcher(nameRole);
                if (m.matches()) {
                    d.asserterName = m.group(1).trim();
                    d.asserterRole = m.group(2).trim();
                } else if (!nameRole.isEmpty()) {
                    d.asserterName = nameRole;
                }
                break;
            }
        }
    }

    private static String stripDiagnosisPrefix(String title) {
        if (title == null) return null;
        int colon = title.indexOf(':');
        return (colon >= 0 ? title.substring(colon + 1) : title).trim();
    }

    /**
     * Pulls the diagnosis name out of a list-row aria-label.
     * Returns null if the pattern doesn't match.
     */
    public static String diagnosisFromAriaLabel(String ariaLabel) {
        if (ariaLabel == null) return null;
        Matcher m = ARIA_DIAGNOSIS.matcher(ariaLabel);
        return m.find() ? m.group(1).trim() : null;
    }

    private static Document parseFragment(String html) {
        return Jsoup.parse(html, "", Parser.htmlParser());
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return null;
    }
}
