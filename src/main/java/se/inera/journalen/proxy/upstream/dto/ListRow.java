package se.inera.journalen.proxy.upstream.dto;

/**
 * One row extracted from a "poll" / "polltimeline" PartialView.
 * Field names mirror the Swedish portal's CSS classes / data attributes.
 */
public class ListRow {
    public String id;          // data-id (UUID)
    public String date;        // data-date (YYYY-MM-DD)
    public String dateTime;    // data-cy-datetime (timeline only, "YYYY-MM-DD HH:mm:ss")
    public String ariaLabel;   // full aria-label of the expander button
    public String authorName;  // text from .AuthorName cell (may be empty)
    public String careUnit;    // text from .CareUnit cell (may be empty)
    public String html;        // raw HTML of the <li> for narrative.text.div

    @Override
    public String toString() {
        return "ListRow{id=" + id + ", date=" + date + ", author=" + authorName + "}";
    }
}
