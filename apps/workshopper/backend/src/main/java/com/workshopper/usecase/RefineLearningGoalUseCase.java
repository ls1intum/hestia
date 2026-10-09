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

    public List<GoalSuggestionDto> execute(RefineGoalRequestDto request) throws Exception {
        var ctx = request.context() != null ? request.context() : java.util.Map.of();
        String sessionType = ctx.getOrDefault("sessionType", "workshop").toString();
        String background = ctx.getOrDefault("studentBackground", "").toString();

        @SuppressWarnings("unchecked")
        java.util.List<String> subSkills = (java.util.List<String>) ctx.getOrDefault("subSkills", java.util.Collections.emptyList());
        String subSkillsContext = "";
        if (!subSkills.isEmpty()) {
            subSkillsContext = "\nAdditionally, this goal is supported by the following granular sub-skills:\n- " 
                    + String.join("\n- ", subSkills)
                    + "\n\nIf the learning goal text above is a broad 'Terminal Competency' that combines many concepts, use these sub-skills as a strong hint for how to split it into distinct, actionable Workshop goals. You can combine closely related sub-skills into a single goal, or elevate major sub-skills into their own goals.";
        }

        return llmService.refineGoal(request, sessionType, background, subSkillsContext);
    }
}
