package se.inera.journalen.proxy.providers;

import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class PatientDemographicsTest {

    @Test
    void extractsBirthDateAndMaleFromTwelveDigitPnr() {
        Patient p = new Patient();
        PatientResourceProvider.populateDemographicsFromPersonnummer(p, "196507132758");
        // 11th digit (0-indexed 10) = '5' → odd → male
        assertThat(p.getGender()).isEqualTo(Enumerations.AdministrativeGender.MALE);
        Date expected = Date.from(LocalDate.of(1965, 7, 13)
                .atStartOfDay(ZoneId.of("Europe/Stockholm")).toInstant());
        assertThat(p.getBirthDate()).isEqualTo(expected);
    }

    @Test
    void extractsFemaleFromEvenGenderDigit() {
        Patient p = new Patient();
        // Hand-built fake personnummer: 1990-01-15, position-11 = '4' (even → female)
        PatientResourceProvider.populateDemographicsFromPersonnummer(p, "199001151245");
        assertThat(p.getGender()).isEqualTo(Enumerations.AdministrativeGender.FEMALE);
        Date expected = Date.from(LocalDate.of(1990, 1, 15)
                .atStartOfDay(ZoneId.of("Europe/Stockholm")).toInstant());
        assertThat(p.getBirthDate()).isEqualTo(expected);
    }

    @Test
    void handlesSamordningsnummer() {
        // Samordningsnummer: day-of-month + 60. 1965-07-(13+60)=73
        Patient p = new Patient();
        PatientResourceProvider.populateDemographicsFromPersonnummer(p, "196507732758");
        Date expected = Date.from(LocalDate.of(1965, 7, 13)
                .atStartOfDay(ZoneId.of("Europe/Stockholm")).toInstant());
        assertThat(p.getBirthDate()).isEqualTo(expected);
    }

    @Test
    void leavesUnsetForInvalidInput() {
        Patient p = new Patient();
        PatientResourceProvider.populateDemographicsFromPersonnummer(p, "not-a-pnr");
        assertThat(p.hasBirthDate()).isFalse();
        assertThat(p.hasGender()).isFalse();
    }
}
