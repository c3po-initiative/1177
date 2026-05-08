package se.inera.journalen.proxy.mapping;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DateUtilTest {

    @Test
    void parsesIsoDate() {
        var d = DateUtil.parseDate("2017-07-19");
        var expected = ZonedDateTime.of(2017, 7, 19, 0, 0, 0, 0, ZoneId.of("Europe/Stockholm"));
        assertThat(d.toInstant()).isEqualTo(expected.toInstant());
    }

    @Test
    void parsesDateTimeWithSeconds() {
        var d = DateUtil.parseDateTime("2025-09-18 11:35:27");
        var expected = ZonedDateTime.of(2025, 9, 18, 11, 35, 27, 0, ZoneId.of("Europe/Stockholm"));
        assertThat(d.toInstant()).isEqualTo(expected.toInstant());
    }

    @Test
    void parsesDateTimeWithoutSeconds() {
        var d = DateUtil.parseDateTime("2014-06-17 13:12");
        var expected = ZonedDateTime.of(2014, 6, 17, 13, 12, 0, 0, ZoneId.of("Europe/Stockholm"));
        assertThat(d.toInstant()).isEqualTo(expected.toInstant());
    }

    @Test
    void parsesDotNetDate() {
        var d = DateUtil.parseDotNetDate("/Date(1778229283926)/");
        assertThat(d.getTime()).isEqualTo(1778229283926L);
    }

    @Test
    void returnsNullOnGarbage() {
        assertThat(DateUtil.parseDate("not-a-date")).isNull();
        assertThat(DateUtil.parseDateTime("garbage")).isNull();
        assertThat(DateUtil.parseDotNetDate("nope")).isNull();
    }
}
