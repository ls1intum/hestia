package de.tum.cit.hestia.teaching.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.teaching.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.core.env.Environment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final ChatModel chatModel;
    private final ResourceLoader resourceLoader;

    @Value("${llm.secondary-model:}")
    private String secondaryModel;

    public LlmService(ChatModel sharedChatModel, ResourceLoader resourceLoader, Environment env) {
        this.chatModel = withTeachingLimits(sharedChatModel, env);
        this.resourceLoader = resourceLoader;
    }

    /**
     * A model call that hangs must fail instead of holding a session's generation lock, and a failing
     * call is retried once. The application-wide chat model has other limits (set for the long
     * extraction runs of materials), so teaching derives its own copy: same model, options and
     * credentials, its own read timeout and retry ladder. Limits: {@code teaching.llm.*}.
     */
    private static ChatModel withTeachingLimits(ChatModel shared, Environment env) {
        if (!(shared instanceof OpenAiChatModel openAi)) {
            return shared;
        }
        Duration connect = duration(env, "teaching.llm.connect-timeout", "10s");
        Duration read = duration(env, "teaching.llm.read-timeout", "90s");
        int attempts = env.getProperty("teaching.llm.retry.max-attempts", Integer.class, 2);

        RestClient.Builder restClient = RestClient.builder().requestFactory(
                ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(connect).withReadTimeout(read)));
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(env.getProperty("spring.ai.openai.base-url", "https://api.openai.com"))
                .apiKey(env.getProperty("spring.ai.openai.api-key", ""))
                .completionsPath(env.getProperty("spring.ai.openai.chat.completions-path", "/v1/chat/completions"))
                .restClientBuilder(restClient)
                .build();
        RetryTemplate retry = RetryTemplate.builder()
                .maxAttempts(attempts)
                .retryOn(TransientAiException.class)
                .retryOn(ResourceAccessException.class)
                .exponentialBackoff(Duration.ofSeconds(2), 2, Duration.ofSeconds(10))
                .build();
        return openAi.mutate().openAiApi(api).retryTemplate(retry).build();
    }

    private static Duration duration(Environment env, String key, String fallback) {
        return DurationStyle.detectAndParse(env.getProperty(key, fallback));
    }

    private String readTemplate(String name) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/" + name + ".st");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not load prompt template " + name, e);
        }
    }

    private <T> T callWithStructuredOutput(String systemTemplateName, String userTemplateName, Map<String, Object> model, BeanOutputConverter<T> converter, boolean useSecondary) {
        String systemText = new PromptTemplate(readTemplate(systemTemplateName)).render(model);
        String userText = new PromptTemplate(readTemplate(userTemplateName)).render(model);
        
        if (converter != null) {
            userText = userText + "\n\n" + converter.getFormat();
        }

        log.debug("Calling LLM (system: {}, user: {})", systemTemplateName, userTemplateName);
        Prompt prompt;
        if (useSecondary && secondaryModel != null && !secondaryModel.isBlank()) {
            prompt = new Prompt(
                    List.of(new SystemMessage(systemText), new UserMessage(userText)),
                    OpenAiChatOptions.builder().model(secondaryModel).build()
            );
        } else {
            prompt = new Prompt(List.of(new SystemMessage(systemText), new UserMessage(userText)));
        }

        String output = chatModel.call(prompt).getResult().getOutput().getText();
        if (converter == null) {
            return (T) output;
        }
        
        try {
            return converter.convert(output);
        } catch (Exception e) {
            // Shortened and on one line: the output contains user text
            String preview = String.valueOf(output).replaceAll("[\\r\\n]+", " ");
            log.error("Failed to parse LLM output ({} chars): {}", String.valueOf(output).length(),
                    preview.length() > 500 ? preview.substring(0, 500) + "…" : preview);
            throw new RuntimeException("LLM output parsing failed", e);
        }
    }

    
    public List<GoalSuggestionDto> refineGoal(RefineGoalRequestDto request, String sessionType, String studentBackground, String subSkillsContext) {
        BeanOutputConverter<List<GoalSuggestionDto>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<List<GoalSuggestionDto>>() {});
        return callWithStructuredOutput("refine-learning-goal-system", "refine-learning-goal-user",
                Map.of("sessionType", sessionType,
                       "studentBackground", studentBackground != null ? studentBackground : "",
                       "goal", request.goal(),
                       "subSkillsContext", subSkillsContext != null ? subSkillsContext : ""),
                converter, false);
    }
    
    
    public List<String> fixGoalsGrammar(List<String> goals) {
        BeanOutputConverter<List<String>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<List<String>>() {});
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < goals.size(); i++) {
            sb.append(i + 1).append(". ").append(goals.get(i)).append("\n");
        }
        
        return callWithStructuredOutput("fix-goals-grammar-system", "fix-goals-grammar-user",
                Map.of("goals", sb.toString()),
                converter, false);
    }
    
    public String generateSessionTitle(WorkshopInputDto meta, String sessionTypeLabel, String skeletonStr, String goalsStr) {
        return callWithStructuredOutput("generate-session-title-system", "generate-session-title-user",
                Map.of("sessionType", sessionTypeLabel,
                       "duration", meta.duration(),
                       "participants", meta.participants(),
                       "studentBackground", meta.studentBackground() != null ? meta.studentBackground() : "",
                       "learningGoals", goalsStr != null ? goalsStr : "",
                       "skeleton", skeletonStr != null ? skeletonStr : ""),
                null, false);
    }
    
    public ActivityBlockDto hydrateActivityBlock(WorkshopInputDto meta, String sessionTypeLabel, String selectedActivitiesStr, String learningGoalsStr, String skeletonBlocksStr, String targetBlockStr, String evaluateRules, String availableMaterials) {
        BeanOutputConverter<ActivityBlockDto> converter = new BeanOutputConverter<>(ActivityBlockDto.class);
        return callWithStructuredOutput("generate-timetable-block-system", "generate-timetable-block-user",
                Map.ofEntries(
                       Map.entry("sessionType", sessionTypeLabel),
                       Map.entry("duration", meta.duration()),
                       Map.entry("participants", meta.participants()),
                       Map.entry("studentBackground", meta.studentBackground() != null ? meta.studentBackground() : ""),
                       Map.entry("selectedActivities", selectedActivitiesStr != null ? selectedActivitiesStr : ""),
                       Map.entry("learningGoals", learningGoalsStr != null ? learningGoalsStr : ""),
                       Map.entry("skeletonBlocks", skeletonBlocksStr != null ? skeletonBlocksStr : ""),
                       Map.entry("targetBlock", targetBlockStr != null ? targetBlockStr : ""),
                       Map.entry("availableMaterials", availableMaterials != null ? availableMaterials : ""),
                       Map.entry("evaluateRules", evaluateRules != null ? evaluateRules : "")
                ),
                converter, false);
    }

    public List<Map<String, Object>> generatePptxSlides(String type, Map<String, Object> model) {
        BeanOutputConverter<List<Map<String, Object>>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
        return callWithStructuredOutput("generate-slide-block-" + type + "-system", "generate-slide-block-" + type + "-user", model, converter, true);
    }

    public Map<String, Object> generatePptxSlide(String type, Map<String, Object> model) {
        BeanOutputConverter<Map<String, Object>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<Map<String, Object>>() {});
        return callWithStructuredOutput("generate-slide-block-" + type + "-system", "generate-slide-block-" + type + "-user", model, converter, true);
    }
}
