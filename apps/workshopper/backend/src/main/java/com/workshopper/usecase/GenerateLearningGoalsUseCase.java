package com.workshopper.usecase;

import com.workshopper.dto.LearningGoalPlanDto;
import com.workshopper.dto.WorkshopInputDto;
import com.workshopper.service.LlmService;
import org.springframework.stereotype.Component;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class GenerateLearningGoalsUseCase {

    private static final Logger log = LoggerFactory.getLogger(GenerateLearningGoalsUseCase.class);
    private final LlmService llmService;

    public GenerateLearningGoalsUseCase(LlmService llmService) {
        this.llmService = llmService;
    }

    public List<LearningGoalPlanDto> execute(WorkshopInputDto input) throws Exception {
        String sessionTypeLabel = resolveSessionType(input.sessionType(), input.sessionTypeOther());
        
        StringBuilder goalsList = new StringBuilder();
        if (input.learningGoals() != null && !input.learningGoals().isEmpty()) {
            for (String g : input.learningGoals()) {
                String cleaned = g.replaceAll("(?i)^\\s*LG\\s*\\d+\\s*[:.-]\\s*", "").trim();
                goalsList.append("  * ").append(cleaned).append("\n");
            }
        }
        
        String doc = input.sourceDocument();
        if (doc != null && doc.length() > 8000) {
            doc = doc.substring(0, 8000) + "\n[... truncated ...]";
        }
        
        log.debug("Generating plan for goals: {}", input.learningGoals());
        return llmService.generateLearningGoals(input, sessionTypeLabel, goalsList.toString(), doc);
    }

    private String resolveSessionType(String type, String other) {
        return switch (type == null ? "workshop" : type) {
            case "lecture" -> "Lecture";
            case "exercise" -> "Exercise session";
            case "seminar" -> "Seminar";
            case "practical" -> "Practical course";
            case "other" -> (other != null && !other.isBlank()) ? other : "Other";
            default -> "Workshop";
        };
    }
}
