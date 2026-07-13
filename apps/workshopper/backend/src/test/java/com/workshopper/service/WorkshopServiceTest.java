package com.workshopper.service;

import com.workshopper.dto.WorkshopInputDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkshopService}.
 * <p>
 * {@link LlmService} is mocked so no real LLM call is ever made.
 * The repository is also mocked — DB integration is not the concern here.
 * These tests focus on prompt-building correctness and business logic.
 */
@ExtendWith(MockitoExtension.class)
class WorkshopServiceTest {

    @Mock
    private LlmService llm;

    @Mock
    private com.workshopper.repository.WorkshopSessionRepository repo;

    private WorkshopService service;

    @BeforeEach
    void setUp() {
        service = new WorkshopService(llm, repo);
    }

    // ── generatePlan — prompt content ─────────────────────────────────────────

    @Nested
    @DisplayName("generatePlan prompt construction")
    class GeneratePlanPromptTests {

        @Test
        @DisplayName("prompt includes session duration and participant count")
        void promptContainsDurationAndParticipants() throws Exception {
            WorkshopInputDto input = new WorkshopInputDto(
                    List.of("Understand regression"), 90, 25,
                    "lecture", null, null, null, null, null, null, null);

            String fakeResponse = "[{\"id\":\"g1\",\"originalGoal\":\"Understand regression\"," +
                    "\"goal\":\"Participants will be able to apply regression\",\"prerequisites\":[]," +
                    "\"achieveActivities\":[],\"assessActivities\":[],\"priority\":0}]";
            when(llm.call(anyString(), anyString())).thenReturn(fakeResponse);
            when(llm.extractJsonArray(anyString())).thenReturn(fakeResponse);

            service.generatePlan(input);

            ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
            verify(llm).call(anyString(), userPromptCaptor.capture());
            String prompt = userPromptCaptor.getValue();

            assertThat(prompt).contains("90");
            assertThat(prompt).contains("25");
        }

        @Test
        @DisplayName("prompt includes provided learning goals")
        void promptContainsLearningGoals() throws Exception {
            WorkshopInputDto input = new WorkshopInputDto(
                    List.of("Explain gradient descent", "Apply cross-validation"),
                    60, 20, "workshop", null, null, null, null, null, null, null);

            String fakeResponse = "[]";
            when(llm.call(anyString(), anyString())).thenReturn(fakeResponse);
            when(llm.extractJsonArray(anyString())).thenReturn(fakeResponse);

            service.generatePlan(input);

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(llm).call(anyString(), promptCaptor.capture());
            String prompt = promptCaptor.getValue();

            assertThat(prompt).contains("Explain gradient descent");
            assertThat(prompt).contains("Apply cross-validation");
        }

        @Test
        @DisplayName("prompt includes student background when provided")
        void promptContainsStudentBackground() throws Exception {
            WorkshopInputDto input = new WorkshopInputDto(
                    List.of("Understand classification"), 45, 30,
                    "exercise", null, "BSc CS students with basic Python knowledge",
                    null, null, null, null, null);

            String fakeResponse = "[]";
            when(llm.call(anyString(), anyString())).thenReturn(fakeResponse);
            when(llm.extractJsonArray(anyString())).thenReturn(fakeResponse);

            service.generatePlan(input);

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(llm).call(anyString(), promptCaptor.capture());
            assertThat(promptCaptor.getValue())
                    .contains("BSc CS students with basic Python knowledge");
        }

        @Test
        @DisplayName("source document text is included in the prompt (truncated at 8000 chars)")
        void sourceDocumentIncludedAndTruncated() throws Exception {
            String longDoc = "x".repeat(9000);
            WorkshopInputDto input = new WorkshopInputDto(
                    null, 60, 20, "seminar", null, null, null,
                    longDoc, null, null, null);

            String fakeResponse = "[]";
            when(llm.call(anyString(), anyString())).thenReturn(fakeResponse);
            when(llm.extractJsonArray(anyString())).thenReturn(fakeResponse);

            service.generatePlan(input);

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(llm).call(anyString(), promptCaptor.capture());
            String prompt = promptCaptor.getValue();

            assertThat(prompt).contains("truncated");
            // The document portion must not exceed 8000 chars plus the truncation marker
            assertThat(prompt).doesNotContain("x".repeat(8001));
        }
    }

    // ── resolveSessionType ────────────────────────────────────────────────────

    @Nested
    @DisplayName("session type label resolution")
    class SessionTypeLabelTests {

        private String callAndCapturePrompt(String type, String other) throws Exception {
            WorkshopInputDto input = new WorkshopInputDto(
                    List.of("A goal"), 60, 20,
                    type, other, null, null, null, null, null, null);

            String fakeResponse = "[]";
            when(llm.call(anyString(), anyString())).thenReturn(fakeResponse);
            when(llm.extractJsonArray(anyString())).thenReturn(fakeResponse);

            service.generatePlan(input);

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(llm).call(anyString(), promptCaptor.capture());
            return promptCaptor.getValue();
        }

        @Test
        @DisplayName("'lecture' maps to 'Lecture'")
        void lectureType() throws Exception {
            assertThat(callAndCapturePrompt("lecture", null)).contains("Lecture");
        }

        @Test
        @DisplayName("'exercise' maps to 'Exercise session'")
        void exerciseType() throws Exception {
            assertThat(callAndCapturePrompt("exercise", null)).contains("Exercise session");
        }

        @Test
        @DisplayName("'seminar' maps to 'Seminar'")
        void seminarType() throws Exception {
            assertThat(callAndCapturePrompt("seminar", null)).contains("Seminar");
        }

        @Test
        @DisplayName("'other' with custom text uses that text")
        void otherTypeWithCustomText() throws Exception {
            assertThat(callAndCapturePrompt("other", "Hackathon")).contains("Hackathon");
        }

        @Test
        @DisplayName("null type defaults to 'Workshop'")
        void nullTypeDefaultsToWorkshop() throws Exception {
            assertThat(callAndCapturePrompt(null, null)).contains("Workshop");
        }
    }

    // ── generateActivities (passthrough) ─────────────────────────────────────

    @Test
    @DisplayName("generateActivities returns goals unchanged (passthrough)")
    void generateActivitiesIsPassthrough() throws Exception {
        var goals = List.of(
                new com.workshopper.dto.LearningGoalPlanDto(
                        "g1", "original", "Participants will apply X",
                        List.of(), List.of(), List.of(), 0));
        WorkshopInputDto meta = new WorkshopInputDto(
                List.of(), 60, 20, "workshop", null, null, null, null, null, null, null);

        var result = service.generateActivities(goals, meta, null);
        assertThat(result).isSameAs(goals);
        verifyNoInteractions(llm);
    }

    // ── fixGoalsGrammar ───────────────────────────────────────────────────────

    @Test
    @DisplayName("fixGoalsGrammar returns input unchanged when goals list is empty")
    void fixGoalsGrammarEmptyList() throws Exception {
        var result = service.fixGoalsGrammar(List.of());
        assertThat(result).isEmpty();
        verifyNoInteractions(llm);
    }

    @Test
    @DisplayName("fixGoalsGrammar returns null when passed null")
    void fixGoalsGrammarNull() throws Exception {
        var result = service.fixGoalsGrammar(null);
        assertThat(result).isNull();
        verifyNoInteractions(llm);
    }
}
