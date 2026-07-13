package de.tum.cit.hestia.learninggoalhub.embedding;

import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    /**
     * Embeds several texts in one model call. The OpenAI/SAIA embedding endpoint accepts a batch of
     * inputs per request, so embedding goals in batches cuts hundreds of per-goal HTTP round trips
     * down to a handful. The returned list is aligned to {@code texts}.
     */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return embeddingModel.embed(texts);
    }
}
