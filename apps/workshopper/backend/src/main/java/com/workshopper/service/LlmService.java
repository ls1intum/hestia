package com.workshopper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.workshopper.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final ChatModel chatModel;
    private final ResourceLoader resourceLoader;

    @Value("${llm.secondary-model:}")
    private String secondaryModel;

    public LlmService(ChatModel chatModel, ResourceLoader resourceLoader) {
        this.chatModel = chatModel;
        this.resourceLoader = resourceLoader;
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
            log.error("Failed to parse LLM output: {}", output);
            throw new RuntimeException("LLM output parsing failed", e);
        }
    }

    public List<LearningGoalPlanDto> generateLearningGoals(WorkshopInputDto input, String sessionTypeLabel, String learningGoalsList, String documentContext) {
        BeanOutputConverter<List<LearningGoalPlanDto>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<List<LearningGoalPlanDto>>() {});
        return callWithStructuredOutput("generate-learning-goals-system", "generate-learning-goals-user",
                Map.of("sessionType", sessionTypeLabel,
                       "duration", input.duration(),
                       "participants", input.participants(),
                       "studentBackground", input.studentBackground() != null ? input.studentBackground() : "",
                       "learningGoals", learningGoalsList != null ? learningGoalsList : "",
                       "sourceDocument", documentContext != null ? documentContext : ""),
                converter, false);
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
    
    public List<String> extractGoalsFromDocument(String sessionType, String studentBackground, String documentText) {
        BeanOutputConverter<List<String>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<List<String>>() {});
        return callWithStructuredOutput("extract-goals-system", "extract-goals-user",
                Map.of("sessionType", sessionType,
                       "studentBackground", studentBackground != null ? studentBackground : "",
                       "document", documentText != null ? documentText : ""),
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
    
    public String generateSessionTitle(SessionSkeletonDto skeleton, List<LearningGoalPlanDto> goals, WorkshopInputDto meta, String sessionTypeLabel, String skeletonStr, String goalsStr) {
        return callWithStructuredOutput("generate-session-title-system", "generate-session-title-user",
                Map.of("sessionType", sessionTypeLabel,
                       "duration", meta.duration(),
                       "participants", meta.participants(),
                       "studentBackground", meta.studentBackground() != null ? meta.studentBackground() : "",
                       "learningGoals", goalsStr != null ? goalsStr : "",
                       "skeleton", skeletonStr != null ? skeletonStr : ""),
                null, false);
    }
    
    public ActivityBlockDto hydrateActivityBlock(SkeletonBlockDto targetBlock, SessionSkeletonDto skeleton, List<LearningGoalPlanDto> goals, WorkshopInputDto meta, String sessionTypeLabel, String selectedActivitiesStr, String learningGoalsStr, String skeletonBlocksStr, String targetBlockStr, String evaluateRules) {
        BeanOutputConverter<ActivityBlockDto> converter = new BeanOutputConverter<>(ActivityBlockDto.class);
        return callWithStructuredOutput("generate-timetable-block-system", "generate-timetable-block-user",
                Map.of("sessionType", sessionTypeLabel,
                       "duration", meta.duration(),
                       "participants", meta.participants(),
                       "interactionLevel", meta.interactionLevel() != null ? meta.interactionLevel() : "",
                       "studentBackground", meta.studentBackground() != null ? meta.studentBackground() : "",
                       "selectedActivities", selectedActivitiesStr != null ? selectedActivitiesStr : "",
                       "learningGoals", learningGoalsStr != null ? learningGoalsStr : "",
                       "skeletonBlocks", skeletonBlocksStr != null ? skeletonBlocksStr : "",
                       "targetBlock", targetBlockStr != null ? targetBlockStr : "",
                       "evaluateRules", evaluateRules != null ? evaluateRules : ""),
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
