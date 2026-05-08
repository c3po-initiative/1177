package se.inera.journalen.proxy.upstream;

import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Enumerations;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import se.inera.journalen.proxy.mapping.SkeletonMappers;
import se.inera.journalen.proxy.upstream.dto.JournalDetail;

import static org.assertj.core.api.Assertions.assertThat;

class JournalDetailTest {

    private final PartialViewParser parser = new PartialViewParser();

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/" + name));
    }

    @Test
    void parsesUnsignedCareDocDetailWithDocbookBody() throws Exception {
        JournalDetail d = parser.parseJournalDetail(fixture("caredoc-detail-1.html"));
        assertThat(d.title).isEqualTo("Inskrivning");
        assertThat(d.timestamp).isEqualTo("2021-09-20 08:33");
        assertThat(d.asserterName).isEqualTo("Åke Lövenhed");
        assertThat(d.asserterRole).isEqualTo("Läkare");
        assertThat(d.careUnit).contains("Medicinska specialistkliniken");
        assertThat(d.signed).isFalse();
        assertThat(d.html).contains("docbook");
    }

    @Test
    void parsesSignedSammanfattning() throws Exception {
        JournalDetail d = parser.parseJournalDetail(fixture("caredoc-detail-3.html"));
        assertThat(d.title).isEqualTo("Sammanfattning");
        assertThat(d.timestamp).isEqualTo("2017-07-19 11:27");
        assertThat(d.asserterName).isEqualTo("Gösta Christensen");
        assertThat(d.asserterRole).isEqualTo("Läkare");
        assertThat(d.signed).isTrue();
    }

    @Test
    void clinicalNoteMapperBuildsRichDocumentReference() throws Exception {
        JournalDetail d = parser.parseJournalDetail(fixture("caredoc-detail-1.html"));
        DocumentReference dr = SkeletonMappers.clinicalNoteFromDetail(
                d, "d55f36af-eb51-4e71-b136-50803efb18ba", "Patient/me");
        assertThat(dr.getIdElement().getIdPart()).isEqualTo("d55f36af-eb51-4e71-b136-50803efb18ba");
        assertThat(dr.getStatus()).isEqualTo(Enumerations.DocumentReferenceStatus.CURRENT);
        assertThat(dr.getDocStatus()).isEqualTo(DocumentReference.ReferredDocumentStatus.PRELIMINARY); // unsigned
        assertThat(dr.getType().getText()).isEqualTo("Inskrivning");
        assertThat(dr.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("clinical-note");
        assertThat(dr.getAuthorFirstRep().getDisplay()).isEqualTo("Åke Lövenhed (Läkare)");
        assertThat(dr.getCustodian().getDisplay()).contains("Medicinska specialistkliniken");
        assertThat(dr.getDate()).isNotNull();
        assertThat(dr.getText().getStatus().toCode()).isEqualTo("generated");
    }

    @Test
    void existingDiagnosisDetailStillParsesAfterRefactor() throws Exception {
        var dd = parser.parseDiagnosisDetail(fixture("diagnosis-detail-2.html"));
        assertThat(dd.title).isEqualTo("Diagnos: Persisterande förmaksflimmer");
        assertThat(dd.headingName).isEqualTo("Persisterande förmaksflimmer");
        assertThat(dd.mainDiagnosis).isEqualTo("Persisterande förmaksflimmer");
        assertThat(dd.asserterName).isEqualTo("Gösta Christensen");
        assertThat(dd.signed).isTrue();
    }
}
