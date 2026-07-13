package com.workshopper.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final ChatModel chatModel;

    @Value("${llm.secondary-model:}")
    private String secondaryModel;

    public LlmService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * Send a prompt to the LLM and return the assistant's reply.
     */
    public String call(String systemPrompt, String userPrompt) {
        log.debug("Calling LLM (primary model)");
        return chatModel.call(new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userPrompt)
        ))).getResult().getOutput().getText();
    }

    /**
     * Send a prompt to the LLM using the secondary model (e.g. for PPTX/PDF generation)
     */
    public String callSecondary(String systemPrompt, String userPrompt) {
        if (secondaryModel == null || secondaryModel.isBlank()) {
            log.warn("Secondary model not configured, falling back to primary model");
            return call(systemPrompt, userPrompt);
        }
        log.debug("Calling LLM with secondary model: {}", secondaryModel);
        return chatModel.call(new Prompt(
                List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
                OpenAiChatOptions.builder().model(secondaryModel).build()
        )).getResult().getOutput().getText();
    }

    public String extractJsonArray(String text) {
        String cleaned = text;
        java.util.regex.Matcher fence = java.util.regex.Pattern
                .compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```")
                .matcher(text);
        if (fence.find()) cleaned = fence.group(1).strip();

        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start == -1 || end == -1 || end < start) {
            log.error("No JSON array found. First 300 chars: {}", text.substring(0, Math.min(300, text.length())));
            throw new IllegalArgumentException("No JSON array found in LLM response");
        }
        return cleaned.substring(start, end + 1);
    }

    public String extractJsonObject(String text) {
        String cleaned = text;
        java.util.regex.Matcher fence = java.util.regex.Pattern
                .compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```")
                .matcher(text);
        if (fence.find()) {
            cleaned = fence.group(1).strip();
            log.debug("Stripped markdown fences from LLM response");
        }

        int start = -1;
        int depth = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start != -1) {
                        return cleaned.substring(start, i + 1);
                    }
                }
            }
        }

        int s = cleaned.indexOf('{');
        int e = cleaned.lastIndexOf('}');
        if (s != -1 && e != -1 && e > s) {
            log.warn("Used fallback naive brace extraction");
            return cleaned.substring(s, e + 1);
        }

        log.error("No JSON found. First 500 chars of LLM response: {}", text.substring(0, Math.min(500, text.length())));
        throw new IllegalArgumentException("No JSON object found in LLM response");
    }
}
