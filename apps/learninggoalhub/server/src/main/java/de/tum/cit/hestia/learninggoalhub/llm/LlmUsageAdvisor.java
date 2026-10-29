package de.tum.cit.hestia.learninggoalhub.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * Counts the tokens of every chat call, so the cost of a run can be measured rather than guessed.
 *
 * <p>A call made while an extraction runs is booked to that run's current phase; the run logs its
 * totals when it ends. Any other call (upload, the review wizard) is logged on its own. Only answered
 * calls are counted: a call that failed or timed out reports no usage.
 */
@Component
public class LlmUsageAdvisor implements CallAdvisor, ChatClientCustomizer {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageAdvisor.class);

    private final LlmUsageSink usageSink;

    public LlmUsageAdvisor(LlmUsageSink usageSink) {
        this.usageSink = usageSink;
    }

    /** Applied to every auto-configured {@code ChatClient.Builder}, so each service gets the advisor. */
    @Override
    public void customize(ChatClient.Builder builder) {
        builder.defaultAdvisors(this);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        ChatResponse chat = response.chatResponse();
        if (chat == null || chat.getMetadata() == null || chat.getMetadata().getUsage() == null) {
            return response;
        }
        Usage usage = chat.getMetadata().getUsage();
        String model = chat.getMetadata().getModel();
        int prompt = orZero(usage.getPromptTokens());
        int completion = orZero(usage.getCompletionTokens());
        // Part of the completion count; gpt-oss spends much of its output here.
        int reasoning = usage.getNativeUsage() instanceof OpenAiApi.Usage openAiUsage
                && openAiUsage.completionTokenDetails() != null
                ? orZero(openAiUsage.completionTokenDetails().reasoningTokens())
                : 0;
        if (!usageSink.record(model, prompt, completion, reasoning)) {
            log.info("LLM call outside an extraction: model={} prompt={} completion={} (reasoning {}) tokens",
                    model, prompt, completion, reasoning);
        }
        return response;
    }

    @Override
    public String getName() {
        return "llm-usage";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
