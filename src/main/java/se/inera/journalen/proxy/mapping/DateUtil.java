package se.inera.journalen.proxy.mapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DateUtil {

    public static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATETIME_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATETIME_SEC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern DOTNET_DATE = Pattern.compile("/Date\\((-?\\d+)\\)/");

    private DateUtil() {}

    /** Parses {@code "YYYY-MM-DD"} into a UTC midnight {@link Date}; returns null on failure. */
    public static Date parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            LocalDate d = LocalDate.parse(s.trim(), DATE);
            return Date.from(d.atStartOfDay(STOCKHOLM).toInstant());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses {@code "YYYY-MM-DD HH:mm[:ss]"} as Europe/Stockholm local time.
     * Returns null on failure.
     */
    public static Date parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        try {
            LocalDateTime ldt;
            if (t.length() <= 16) {
                ldt = LocalDateTime.parse(t, DATETIME_MIN);
            } else {
                ldt = LocalDateTime.parse(t, DATETIME_SEC);
            }
            ZonedDateTime zdt = ldt.atZone(STOCKHOLM);
            return Date.from(zdt.toInstant());
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses ASP.NET {@code /Date(1234567890123)/} format. */
    public static Date parseDotNetDate(String s) {
        if (s == null) return null;
        Matcher m = DOTNET_DATE.matcher(s);
        if (!m.find()) return null;
        try {
            return new Date(Long.parseLong(m.group(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
