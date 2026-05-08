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

    /** Swedish prose form: e.g. "onsdag 19 november 2025 klockan 14:43" (the weekday is optional). */
    private static final Pattern SWEDISH_PROSE = Pattern.compile(
            "(?:\\p{L}+\\s+)?(\\d{1,2})\\s+(\\p{L}+)\\s+(\\d{4})(?:\\s+klockan\\s+(\\d{1,2}):(\\d{2}))?",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static final java.util.Map<String, Integer> SWEDISH_MONTHS = java.util.Map.ofEntries(
            java.util.Map.entry("januari", 1), java.util.Map.entry("februari", 2),
            java.util.Map.entry("mars", 3),    java.util.Map.entry("april", 4),
            java.util.Map.entry("maj", 5),     java.util.Map.entry("juni", 6),
            java.util.Map.entry("juli", 7),    java.util.Map.entry("augusti", 8),
            java.util.Map.entry("september", 9),java.util.Map.entry("oktober", 10),
            java.util.Map.entry("november", 11),java.util.Map.entry("december", 12)
    );

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
     * Parses {@code "YYYY-MM-DD HH:mm[:ss]"} or the Swedish prose form
     * {@code "<weekday> <day> <month> <year> klockan <HH>:<MM>"}, both as Europe/Stockholm
     * local time. Returns null on failure.
     */
    public static Date parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        // ISO first
        try {
            LocalDateTime ldt;
            if (t.length() <= 16 && t.length() >= 10 && t.charAt(4) == '-') {
                ldt = LocalDateTime.parse(t, DATETIME_MIN);
            } else if (t.length() >= 19 && t.charAt(4) == '-') {
                ldt = LocalDateTime.parse(t, DATETIME_SEC);
            } else {
                ldt = null;
            }
            if (ldt != null) return Date.from(ldt.atZone(STOCKHOLM).toInstant());
        } catch (Exception ignored) {
            // fall through to Swedish prose
        }
        // Swedish prose
        Matcher m = SWEDISH_PROSE.matcher(t);
        if (m.find()) {
            try {
                int day = Integer.parseInt(m.group(1));
                Integer month = SWEDISH_MONTHS.get(m.group(2).toLowerCase(java.util.Locale.ROOT));
                int year = Integer.parseInt(m.group(3));
                if (month != null) {
                    int hour = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
                    int minute = m.group(5) != null ? Integer.parseInt(m.group(5)) : 0;
                    LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, minute);
                    return Date.from(ldt.atZone(STOCKHOLM).toInstant());
                }
            } catch (Exception ignored) {}
        }
        return null;
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
