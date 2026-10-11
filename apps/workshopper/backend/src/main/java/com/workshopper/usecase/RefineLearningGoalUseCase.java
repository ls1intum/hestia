package com.workshopper.usecase;

import com.workshopper.dto.GoalSuggestionDto;
import com.workshopper.dto.RefineGoalRequestDto;
import com.workshopper.service.LlmService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RefineLearningGoalUseCase {

    private final LlmService llmService;

    public RefineLearningGoalUseCase(LlmService llmService) {
        this.llmService = llmService;
    }

    public List<GoalSuggestionDto> execute(RefineGoalRequestDto request) {
        var ctx = request.context() != null ? request.context() : java.util.Map.of();
        String sessionType = ctx.getOrDefault("sessionType", "workshop").toString();
        String background = ctx.getOrDefault("studentBackground", "").toString();

        StringBuilder subSkillsContext = new StringBuilder();
        Object subSkillsObj = ctx.get("subSkills");
        if (subSkillsObj instanceof List<?> subSkills && !subSkills.isEmpty()) {
            subSkillsContext.append("The sub-skills for this goal are:\n");
            for (Object skill : subSkills) {
                subSkillsContext.append("- ").append(skill).append("\n");
            }
        }

        return llmService.refineGoal(request, sessionType, background, subSkillsContext.toString());
    }
}
