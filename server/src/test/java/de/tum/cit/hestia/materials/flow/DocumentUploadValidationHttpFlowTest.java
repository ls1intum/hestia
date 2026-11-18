package de.tum.cit.hestia.materials.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** What an upload refuses, and that a refused upload stores nothing. */
@Tag("http-flow")
class DocumentUploadValidationHttpFlowTest extends HttpFlowTestBase {

    @Test
    void aFileThatIsNotAPdfIsRejectedAndNothingIsStored() {
        long courseId = createCourse("Flow test: bad upload");
        byte[] notAPdf = "just some text, not a PDF".getBytes(StandardCharsets.UTF_8);

        HubApi.Response rejected = api().upload("/courses/" + courseId + "/documents",
                Map.of("notes.txt", notAPdf), "text/plain");
        assertThat(rejected.status()).isEqualTo(415);
        assertThat(rejected.json().path("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");

        // A text file renamed to .pdf is caught by its content.
        HubApi.Response renamed = api().upload("/courses/" + courseId + "/documents",
                Map.of("renamed.pdf", notAPdf), "application/pdf");
        assertThat(renamed.status()).isEqualTo(415);

        assertThat(api().get("/courses/" + courseId + "/documents").json().size()).isZero();
    }

    @Test
    void uploadingToAnUnknownCourseAnswers404() {
        HubApi.Response missing = api().upload("/courses/999999999/documents",
                Map.of("a.pdf", TestPdfs.textPdf(TestPdfs.SEARCHING_LECTURE)), "application/pdf");
        assertThat(missing.status()).isEqualTo(404);
    }
}
