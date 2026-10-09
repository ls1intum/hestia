package com.workshopper.usecase;

import com.workshopper.service.LlmService;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class FixGoalsGrammarUseCase {

    private final LlmService llmService;

    public FixGoalsGrammarUseCase(LlmService llmService) {
        this.llmService = llmService;
    }

    public List<String> execute(List<String> goals) throws Exception {
        if (goals == null || goals.isEmpty())
            return goals;
        return llmService.fixGoalsGrammar(goals);
    }
}
