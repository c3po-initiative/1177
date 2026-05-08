package se.inera.journalen.proxy.mapping;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

public final class NarrativeUtil {

    private static final Safelist SAFE = Safelist.relaxed()
            .addAttributes(":all", "class")
            .addTags("section", "article", "header", "footer", "nav", "main", "aside")
            .removeTags("script", "iframe", "object", "embed", "form", "input", "button");

    private NarrativeUtil() {}

    /**
     * Wraps an upstream HTML fragment in an {@code xmlns="http://www.w3.org/1999/xhtml"}
     * div, after sanitizing scripts/forms/buttons out of it and converting the body to
     * well-formed XHTML so HAPI's strict narrative parser will accept it.
     */
    public static String wrap(String html) {
        if (html == null || html.isEmpty()) {
            return "<div xmlns=\"http://www.w3.org/1999/xhtml\"></div>";
        }
        Document dirty = Jsoup.parseBodyFragment(html);
        Document clean = new Cleaner(SAFE).clean(dirty);
        clean.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)   // self-closes <br>, <img>, etc.
                .escapeMode(Entities.EscapeMode.xhtml)
                .prettyPrint(false);
        String body = clean.body().html();
        return "<div xmlns=\"http://www.w3.org/1999/xhtml\">" + body + "</div>";
    }
}
