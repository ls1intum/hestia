package de.tum.cit.hestia.materials.llm;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.ai.LlmContent;
import de.tum.cit.hestia.ai.LlmGateway;
import de.tum.cit.hestia.ai.LlmRequest;
import de.tum.cit.hestia.ai.LlmResponse;
import de.tum.cit.hestia.ai.ModelRef;
import de.tum.cit.hestia.ai.Provider;
import de.tum.cit.hestia.materials.extraction.ExtractionProgressTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LlmClientTest {

    record Answer(String value) {}

    private final List<LlmRequest> sent = new ArrayList<>();

    private LlmGateway gateway(String text, LlmResponse.Usage usage) {
        return request -> {
            sent.add(request);
            return new LlmResponse(text, null, "test-model", Provider.LOGOS, usage);
        };
    }

    @Test
    void booksCallsToTheActiveRunsPhaseAndModel() {
        ExtractionProgressTracker tracker = new ExtractionProgressTracker();
        ExtractionProgressTracker.Run run = tracker.tryStart(java.util.UUID.randomUUID(), null).orElseThrow();
        run.phase(ExtractionProgressTracker.Phase.EXTRACTING, 2);

        new LlmClient(gateway("ok", new LlmResponse.Usage(1000, 300, 200)), tracker)
                .text(LlmClient.LlmRequest.of("taxonomy", "hi"));
        new LlmClient(gateway("ok", new LlmResponse.Usage(500, 100, 50)), tracker)
                .text(LlmClient.LlmRequest.of("taxonomy", "hi"));

        assertThat(run.usageReport())
                .startsWith("total: 2 calls, 1500 prompt + 400 completion (of which 250 reasoning) tokens")
                .contains("EXTRACTING / test-model: 2 calls, 1500 prompt + 400 completion");
    }

    @Test
    void leavesRunsUntouchedWhenNoExtractionIsActive() {
        ExtractionProgressTracker tracker = new ExtractionProgressTracker();

        String reply = new LlmClient(gateway("ok", new LlmResponse.Usage(10, 5, null)), tracker)
                .text(LlmClient.LlmRequest.of("taxonomy", "hi"));

        assertThat(reply).isEqualTo("ok");
        assertThat(tracker.activeRun()).isEmpty();
    }

    @Test
    void sendsModuleAndPurposeCourseModelAndSystem() {
        UUID course = UUID.randomUUID();
        new LlmClient(gateway("ok", null), new ExtractionProgressTracker()).text(
                LlmClient.LlmRequest.of("page-description", "hi").forCourse(course).withSystem("sys")
                        .withModel("m1").withTemperature(0.2));

        LlmRequest request = sent.getFirst();
        assertThat(request.module()).isEqualTo("materials");
        assertThat(request.purpose()).isEqualTo("page-description");
        assertThat(request.courseId()).isEqualTo(course);
        assertThat(request.model()).isEqualTo(ModelRef.logos("m1"));
        assertThat(request.system()).isEqualTo("sys");
        assertThat(request.temperature()).isEqualTo(0.2);
    }

    @Test
    void blankModelMeansTheDefaultAndImagesGoAsDataUrls() {
        new LlmClient(gateway("ok", null), new ExtractionProgressTracker()).text(
                LlmClient.LlmRequest.of("document-title", "hi").withModel(" ")
                        .withImages(List.of(LlmClient.Image.png(new byte[] {1, 2, 3}))));

        LlmRequest request = sent.getFirst();
        assertThat(request.model()).isEqualTo(ModelRef.logos());
        assertThat(request.user()).isInstanceOfSatisfying(LlmContent.MultipartContent.class, content -> {
            assertThat(content.parts()).hasSize(2);
            assertThat(content.parts().get(0)).isEqualTo(new LlmContent.TextPart("hi"));
            assertThat(content.parts().get(1)).isEqualTo(new LlmContent.ImageUrlPart("data:image/png;base64,AQID"));
        });
    }

    @Test
    void structuredRepliesGetTheFormatInstructionsAndAreBoundLeniently() {
        LlmClient client = new LlmClient(gateway("{\"value\": \"a\\(b\"}", null), new ExtractionProgressTracker());

        Answer answer = client.entity(LlmClient.LlmRequest.of("taxonomy", "classify"), Answer.class);

        assertThat(answer).isEqualTo(new Answer("a(b"));
        String user = ((LlmContent.TextContent) sent.getFirst().user()).text();
        assertThat(user).startsWith("classify").contains("JSON").contains("\"value\"");
    }

    @Test
    void aBlankReplyBindsToNull() {
        LlmClient client = new LlmClient(gateway("  ", null), new ExtractionProgressTracker());

        assertThat(client.entity(LlmClient.LlmRequest.of("taxonomy", "x"), Answer.class)).isNull();
    }
}
