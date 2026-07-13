package de.tum.cit.hestia.learninggoalhub.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class EmbeddingServiceTest {

    @Test
    void returnsVectorFromEmbeddingModel() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        float[] expected = new float[] {0.1f, 0.2f, 0.3f};
        when(embeddingModel.embed(eq("Apply TDD."))).thenReturn(expected);

        float[] result = new EmbeddingService(embeddingModel).embed("Apply TDD.");

        assertThat(result).isEqualTo(expected);
        verify(embeddingModel).embed("Apply TDD.");
    }

    @Test
    void embedsTextsAsOneBatchCall() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        List<String> texts = List.of("a", "b");
        List<float[]> expected = List.of(new float[] {0.1f}, new float[] {0.2f});
        when(embeddingModel.embed(eq(texts))).thenReturn(expected);

        List<float[]> result = new EmbeddingService(embeddingModel).embedAll(texts);

        assertThat(result).isEqualTo(expected);
        verify(embeddingModel).embed(texts);
    }

    @Test
    void returnsEmptyForEmptyBatch() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        assertThat(new EmbeddingService(embeddingModel).embedAll(List.of())).isEmpty();
    }
}
