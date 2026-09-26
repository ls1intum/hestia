package com.workshopper.usecase;

import com.workshopper.dto.ExtractGoalsRequestDto;
import com.workshopper.service.LlmService;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class ExtractGoalsUseCase {

    private final LlmService llmService;

    public ExtractGoalsUseCase(LlmService llmService) {
        this.llmService = llmService;
    }

    public List<String> execute(ExtractGoalsRequestDto request) throws Exception {
        var ctx = request.context() != null ? request.context() : java.util.Map.of();
        String sessionType = ctx.getOrDefault("sessionType", "workshop").toString();
        String background = ctx.getOrDefault("studentBackground", "").toString();

        // Truncate document to avoid exceeding token budget
        String doc = request.documentText();
        if (doc != null && doc.length() > 10000)
            doc = doc.substring(0, 10000) + "\n[... truncated ...]";

        return llmService.extractGoalsFromDocument(sessionType, background, doc);
    }
}
