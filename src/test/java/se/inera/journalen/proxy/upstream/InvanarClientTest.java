package se.inera.journalen.proxy.upstream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InvanarClientTest {

    @Test
    void extractsCsrfFromHiddenInput() {
        String html = "<html><body><form>" +
                "<input name=\"__RequestVerificationToken\" type=\"hidden\" value=\"abc123==\" />" +
                "</form></body></html>";
        assertThat(InvanarClient.extractCsrfToken(html)).isEqualTo("abc123==");
    }

    @Test
    void returnsNullWhenAbsent() {
        assertThat(InvanarClient.extractCsrfToken("<html></html>")).isNull();
    }
}
