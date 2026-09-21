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

    public List<LearningGoalPlanDto> execute(WorkshopInputDto input) {
        String sessionTypeLabel = "workshop".equals(input.sessionType()) ? "interactive workshop" : "university lecture";
        String doc = input.uploadedMaterialsText() != null ? input.uploadedMaterialsText() : "";
        StringBuilder goalsList = new StringBuilder();
        if (input.learningGoals() != null) {
            for (int i = 0; i < input.learningGoals().size(); i++) {
                goalsList.append("LG").append(i + 1).append(": ").append(input.learningGoals().get(i)).append("\n");
            }
        }
        
        log.debug("Generating plan for goals: {}", input.learningGoals());
        return llmService.generateLearningGoals(input, sessionTypeLabel, goalsList.toString(), doc);
    }
}
