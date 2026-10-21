package de.tum.cit.hestia.learninggoalhub.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.extraction.ExtractionProgressTracker;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;

class LlmUsageAdvisorTest {

    @Test
    void booksCallsToTheActiveRunsPhaseAndModel() {
        ExtractionProgressTracker tracker = new ExtractionProgressTracker();
        ExtractionProgressTracker.Run run = tracker.tryStart(1L, null).orElseThrow();
        run.phase(ExtractionProgressTracker.Phase.EXTRACTING, 2);
        LlmUsageAdvisor advisor = new LlmUsageAdvisor(tracker);

        advisor.adviseCall(request(), chainAnswering(1000, 300, 200));
        advisor.adviseCall(request(), chainAnswering(500, 100, 50));

        assertThat(run.usageReport())
                .startsWith("total: 2 calls, 1500 prompt + 400 completion (of which 250 reasoning) tokens")
                .contains("EXTRACTING / test-model: 2 calls, 1500 prompt + 400 completion");
    }

    @Test
    void leavesRunsUntouchedWhenNoExtractionIsActive() {
        ExtractionProgressTracker tracker = new ExtractionProgressTracker();
        LlmUsageAdvisor advisor = new LlmUsageAdvisor(tracker);

        ChatClientResponse response = advisor.adviseCall(request(), chainAnswering(10, 5, 0));

        assertThat(response.chatResponse()).isNotNull();
        assertThat(tracker.activeRun()).isEmpty();
    }

    private static ChatClientRequest request() {
        return ChatClientRequest.builder().prompt(new Prompt("hi")).build();
    }

    private static CallAdvisorChain chainAnswering(int prompt, int completion, int reasoning) {
        OpenAiApi.Usage nativeUsage = new OpenAiApi.Usage(completion, prompt, prompt + completion, null,
                new OpenAiApi.Usage.CompletionTokenDetails(reasoning, null, null, null));
        ChatResponse chat = new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))),
                ChatResponseMetadata.builder()
                        .model("test-model")
                        .usage(new DefaultUsage(prompt, completion, prompt + completion, nativeUsage))
                        .build());
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(new ChatClientResponse(chat, Map.of()));
        return chain;
    }
}
