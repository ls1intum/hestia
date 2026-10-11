package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;
import de.tum.cit.hestia.learninggoalhub.document.DocumentKind;
import de.tum.cit.hestia.learninggoalhub.document.LanguageDetectionService;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.StructuredOutputConverter;

class SessionExtractionServiceTest {

    /**
     * The request spec returns itself from system() and options() so the whole call chain is one
     * mock. Rebuilding the chain inside a verify() would otherwise be recorded as another
     * invocation and make every count off by one.
     */
    private static ChatClient.ChatClientRequestSpec stubSpec(ChatClient chatClient) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.options(any(ChatOptions.class))).thenReturn(spec);
        return spec;
    }

    @Test
    void returnsStructuredOutcomesFromChatClient() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        List<ExtractedSkill> expected = List.of(
                new ExtractedSkill("Applying the testing strategy in representative projects.",
                        "Apply Testing Strategy", GoalKind.EXPLICIT,
                        BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0,
                        List.of(new ExtractedSkill.Knowledge(
                                "Explaining the principles behind the testing strategy.",
                                "Explain Testing Strategy",
                                GoalKind.EXPLICIT, BloomLevel.UNDERSTAND, SoloLevel.RELATIONAL, 0, 0))),
                new ExtractedSkill("Applying the strategy to a small project.", "Apply Testing Practice",
                        GoalKind.IMPLICIT,
                        BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, List.of()));
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(expected);

        clearInvocations(spec);
        List<ExtractedSkill> result = new SessionExtractionService(builder, mock(LanguageDetectionService.class), 0.2)
                .extract("Session 4: Testing", "FULL-SESSION-MARKER-42");

        assertThat(result).containsExactlyElementsOf(expected);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Session 4: Testing")
                .contains("[0] FULL-SESSION-MARKER-42")
                // Asserted in fragments: the template wraps both sentences across two lines.
                .contains("Return at most 4.")
                .contains("outcomes at APPLY, ANALYZE, EVALUATE")
                .contains("Knowledge sits at REMEMBER or UNDERSTAND")
                .contains("Return an empty list when that is the case")
                // Knowledge must be demanded as an OUTCOME, not as a bare fact: the word
                // "declarative" used to licence propositions and produced 55% bare statements.
                .doesNotContain("declarative")
                .contains("expanded action-noun")
                .contains("form naming what the student does with it")
                .contains("Never state a bare fact")
                .contains("WRONG:")
                .contains("RIGHT:")
                .contains("The wording invariant and every")
                .contains("source-line rule")
                // Guards the recomposed-quote failure: heading + its bullets read as one block.
                .contains("are SEPARATE")
                .contains("learning objectives")
                .contains("Choose each outcome's verb by what the STUDENT")
                .contains("Do not invent outcomes")
                .contains("shortLabel")
                .contains("2-6 word label naming the action and its topic")
                .contains("sourceStartLine")
                .contains("sourceEndLine")
                .doesNotContain("sourceSnippet");
    }

    @Test
    void retriesCompleteSessionWhenARequiredTextFieldIsMissing() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ExtractedSkill malformed = new ExtractedSkill(null, "Diskrete Teilmengen charakterisieren",
                GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, List.of());
        ExtractedSkill corrected = new ExtractedSkill(
                "Charakterisieren diskreter Teilmengen anhand ihrer Häufungspunkte.",
                "Diskrete Teilmengen charakterisieren", GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, List.of());
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(malformed), List.of(corrected));
        clearInvocations(spec);

        List<ExtractedSkill> result = new SessionExtractionService(
                builder, mock(LanguageDetectionService.class), 0.2)
                .extract("Vorlesung 1", "text", null, "German", null, List.of());

        assertThat(result).containsExactly(corrected);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec, times(2)).user(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues().get(1))
                .contains("previous response violated")
                .contains("Regenerate the")
                .contains("COMPLETE response")
                .contains("distinct text")
                .contains("action-noun wording");
    }

    @Test
    void anExerciseUnitGetsTheExercisePromptAndTheSameValidation() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ExtractedSkill outOfRange = new ExtractedSkill(
                "Computing the determinant of a 3x3 matrix.", "Compute determinants",
                GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.UNISTRUCTURAL, 8, 9, List.of());
        ExtractedSkill corrected = new ExtractedSkill(
                "Computing the determinant of a 3x3 matrix.", "Compute determinants",
                GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.UNISTRUCTURAL, 0, 0, List.of());
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(outOfRange), List.of(corrected));
        clearInvocations(spec);

        List<ExtractedSkill> result = new SessionExtractionService(builder, mock(LanguageDetectionService.class), 0.2)
                .extract("Sheet 4", "Task 1: Compute det(A).", null, "English", null, List.of(), 2,
                        DocumentKind.EXERCISE);

        assertThat(result).containsExactly(corrected);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec, times(2)).user(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues().get(0))
                .startsWith(SessionExtractionService.EXERCISE_PROMPT_TEMPLATE.substring(0, 80))
                .contains("what\nthe student does in its tasks")
                .contains("Return at most 2.")
                .contains("Exercise title:\n---\nSheet 4")
                .contains("[0] Task 1: Compute det(A).")
                .doesNotContain("what a student should");
        assertThat(promptCaptor.getAllValues().get(1))
                .contains("previous response violated")
                .contains("Specific validation failure");
    }

    @Test
    void aLectureOrKindlessUnitKeepsTheLecturePrompt() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class))).thenReturn(List.of());
        clearInvocations(spec);
        SessionExtractionService service =
                new SessionExtractionService(builder, mock(LanguageDetectionService.class), 0.2);

        service.extract("Lecture 4", "text", null, "English", null, List.of(), 2, DocumentKind.LECTURE);
        service.extract("Lecture 4", "text", null, "English", null, List.of(), 2, null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec, times(2)).user(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues()).allSatisfy(prompt -> assertThat(prompt)
                .startsWith(SessionExtractionService.PROMPT_TEMPLATE.substring(0, 80))
                .doesNotContain("Exercise title:"));
    }

    @Test
    void rejectsSessionWhenCorrectionRetryStillHasMissingText() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ExtractedSkill malformed = new ExtractedSkill(null, "Missing text",
                GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, List.of());
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(malformed));
        clearInvocations(spec);

        SessionExtractionService service = new SessionExtractionService(
                builder, mock(LanguageDetectionService.class), 0.2);

        assertThatThrownBy(() -> service.extract(
                "Vorlesung 1", "text", null, "German", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank text");
        verify(spec, times(2)).user(anyString());
    }

    @Test
    void rejectsMoreBroadSkillsThanOneSessionMayTeach() {
        List<ExtractedSkill> overfull = java.util.stream.IntStream.rangeClosed(
                        1, SessionExtractionService.MAX_SKILLS_PER_SESSION + 1)
                .mapToObj(index -> new ExtractedSkill(
                        "Applying method " + index + " in representative contexts.",
                        "Apply Method " + index, GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, List.of()))
                .toList();

        assertThatThrownBy(() -> SessionExtractionService.validate(overfull, "English"))
                .hasMessageContaining("more than 4 broad skills");
    }

    @Test
    void retriesCompleteSessionWhenSourceRangeIsOutsideNumberedText() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ExtractedSkill invalid = new ExtractedSkill(
                "Applying a method to representative examples.", "Apply Method",
                GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 8, 9, List.of());
        ExtractedSkill corrected = new ExtractedSkill(
                "Applying a method to representative examples.", "Apply Method",
                GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 1, List.of());
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(invalid), List.of(corrected));
        clearInvocations(spec);

        List<ExtractedSkill> result = new SessionExtractionService(
                builder, mock(LanguageDetectionService.class), 0.2)
                .extract("Lecture", "first line\nsecond line", null, "English", null, List.of());

        assertThat(result).containsExactly(corrected);
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec, times(2)).user(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues().get(1))
                .contains("Specific validation failure")
                .contains("invalid source range [8..9]")
                .contains("only the structured JSON");
    }

    @Test
    void rejectsMissingPartialAndAmbiguousEvidenceAfterRetry() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ExtractedSkill missing = new ExtractedSkill(
                "Applying a method to representative examples.", "Apply Method",
                GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, null, null, List.of());
        ExtractedSkill partial = new ExtractedSkill(
                "Applying a method to representative examples.", "Apply Method",
                GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, null, List.of());
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(missing), List.of(partial));
        clearInvocations(spec);

        SessionExtractionService service = new SessionExtractionService(
                builder, mock(LanguageDetectionService.class), 0.2);

        assertThatThrownBy(() -> service.extract(
                "Lecture", "one line", null, "English", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both sourceStartLine and sourceEndLine");
        verify(spec, times(2)).user(anyString());
    }

    @Test
    void salvagesIndividuallyGroundedOutcomesAfterInvalidCorrectionRetry() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ExtractedSkill invalidFirst = new ExtractedSkill(
                "Applying a method to representative examples.", "Apply Method",
                GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, null, null, List.of());
        ExtractedSkill validSkill = new ExtractedSkill(
                "Applying a method to representative examples.", "Apply Method",
                GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, List.of(
                        new ExtractedSkill.Knowledge(
                                "Explaining the method's central assumption in context.",
                                "Explain Central Assumption", GoalKind.IMPLICIT, BloomLevel.UNDERSTAND, SoloLevel.RELATIONAL, 1, 1),
                        new ExtractedSkill.Knowledge(
                                "Identifying an unsupported detail in the example.",
                                "Identify Unsupported Detail", GoalKind.IMPLICIT, BloomLevel.UNDERSTAND, SoloLevel.RELATIONAL, null, null)));
        ExtractedSkill invalidSkill = new ExtractedSkill(
                "Applying an unsupported procedure to examples.", "Apply Unsupported Procedure",
                GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 9, 9, List.of());
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(invalidFirst), List.of(validSkill, invalidSkill));
        clearInvocations(spec);

        List<ExtractedSkill> result = new SessionExtractionService(
                builder, mock(LanguageDetectionService.class), 0.2)
                .extract("Lecture", "method\nassumption", null, "English", null, List.of());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().text()).isEqualTo(validSkill.text());
        assertThat(result.getFirst().knowledge())
                .extracting(ExtractedSkill.Knowledge::text)
                .containsExactly("Explaining the method's central assumption in context.");
        verify(spec, times(2)).user(anyString());
    }

    @Test
    void acceptsFigureOnlyEvidence() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ExtractedSkill figureOnly = new ExtractedSkill(
                "Applying a visual method to representative examples.", "Apply Visual Method",
                GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, null, null, 0, List.of());
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(figureOnly));
        clearInvocations(spec);

        List<ExtractedSkill> result = new SessionExtractionService(
                builder, mock(LanguageDetectionService.class), 0.2)
                .extract("Lecture", "one line", null, "English", null,
                        List.of(new PageDescriptionService.FigureDescription(1, "Diagram")));

        assertThat(result).containsExactly(figureOnly);
        verify(spec).user(anyString());
    }

    /**
     * Citing both no longer costs a correction round trip. The redundancy is resolved where it is
     * found — text first, figure as the fallback — so a session whose skills all cite both is
     * extracted on the first attempt instead of retried and then failed.
     */
    @Test
    void doesNotRetryWhenTheModelCitesLinesAndAFigureAtOnce() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ExtractedSkill both = new ExtractedSkill(
                "Applying a visual method to representative examples.", "Apply Visual Method",
                GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, 0, List.of());
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(both));
        clearInvocations(spec);

        List<ExtractedSkill> result = new SessionExtractionService(
                builder, mock(LanguageDetectionService.class), 0.2)
                .extract("Lecture", "one line", null, "English", null,
                        List.of(new PageDescriptionService.FigureDescription(1, "Diagram")));

        assertThat(result).singleElement()
                .satisfies(skill -> {
                    assertThat(skill.sourceStartLine()).isEqualTo(0);
                    assertThat(skill.sourceEndLine()).isEqualTo(0);
                    assertThat(skill.sourceFigure()).isNull();
                });
        verify(spec).user(anyString());
    }

    @Test
    void ignoresStructuredOutputFigurePlaceholderWhenNoFiguresWereOffered() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ExtractedSkill modelResponse = new ExtractedSkill(
                "Applying a textual method to representative examples.", "Apply Textual Method",
                GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, 0, List.of(
                        new ExtractedSkill.Knowledge(
                                "Explaining the textual method's central assumption.",
                                "Explain Central Assumption", GoalKind.IMPLICIT, BloomLevel.UNDERSTAND, SoloLevel.RELATIONAL, 1, 1, 0)));
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of(modelResponse));
        clearInvocations(spec);

        List<ExtractedSkill> result = new SessionExtractionService(
                builder, mock(LanguageDetectionService.class), 0.2)
                .extract("Lecture", "method\nassumption", null, "English", null, List.of());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().sourceFigure()).isNull();
        assertThat(result.getFirst().knowledge().getFirst().sourceFigure()).isNull();
        verify(spec).user(anyString());
    }

    @Test
    void appliesModelOverrideWhenProvided() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        clearInvocations(spec);
        new SessionExtractionService(builder, mock(LanguageDetectionService.class), 0.2)
                .extract("title", "text", "German", "qwen3.6-35b-a3b");

        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(spec).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().getModel()).isEqualTo("qwen3.6-35b-a3b");
    }

    @Test
    void instructsModelToUseRequestedLanguageAndReturnLineIndices() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        clearInvocations(spec);
        new SessionExtractionService(builder, mock(LanguageDetectionService.class), 0.2)
                .extract("title", "text", "German", null);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("in German")
                .contains("never translate")
                .contains("Final language requirement")
                .contains("EXPLICIT or IMPLICIT");
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec).system(systemCaptor.capture());
        assertThat(systemCaptor.getValue())
                .contains("every GENERATED field")
                .contains("must be written in German")
                .contains("Verbatim quotes")
                .contains("Do not translate");
    }

    @Test
    void emptyFigureListLeavesTheDirectPromptWithoutFigureInstructions() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        clearInvocations(spec);
        SessionExtractionService service = new SessionExtractionService(builder, mock(LanguageDetectionService.class), 0.2);
        service.extract("title", "text", "English", null);
        service.extract("title", "text", null, "English", null, List.of());

        verify(spec, times(2)).user(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues().get(0))
                .isEqualTo(promptCaptor.getAllValues().get(1));
        assertThat(promptCaptor.getAllValues().get(1))
                .doesNotContain("Figure descriptions")
                .doesNotContain("sourceFigure")
                .contains("[0] text");
    }

    @Test
    void appendsFigureDescriptionsAndFigureOnlySourceRuleWhenProvided() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        clearInvocations(spec);
        new SessionExtractionService(builder, mock(LanguageDetectionService.class), 0.2)
                .extract("title", "text", null, "English", null,
                List.of(new PageDescriptionService.FigureDescription(12, "A process diagram.")));

        verify(spec).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Figure descriptions (AI-generated from rendered slides — NOT verbatim text):")
                .contains("[F0] (page 12) A process diagram.")
                .contains("ONLY when no numbered lines support an outcome")
                .contains("sourceFigure")
                .contains("Final language requirement");
    }

    @Test
    void retriesOnceWhenGeneratedLanguageConfidentlyMismatches() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        List<ExtractedSkill> first = List.of(new ExtractedSkill(
                "Anwenden einer englischen Methode in repräsentativen Kontexten.", "Englische Methode anwenden",
                GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, List.of()));
        List<ExtractedSkill> retry = List.of(new ExtractedSkill(
                "Anwenden einer deutschen Methode in repräsentativen Kontexten.", "Deutsche Methode anwenden",
                GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, List.of()));
        ChatClient.ChatClientRequestSpec spec = stubSpec(chatClient);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(first, retry);
        LanguageDetectionService detector = mock(LanguageDetectionService.class);
        when(detector.detect(anyString())).thenReturn("en", "de");

        List<ExtractedSkill> result = new SessionExtractionService(builder, detector, 0.2)
                .extract("title", "text", "de", "German", null);

        assertThat(result).containsExactlyElementsOf(retry);
        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(spec, times(2)).options(optionsCaptor.capture());
        assertThat(optionsCaptor.getAllValues().get(0).getTemperature()).isEqualTo(0.2);
        assertThat(optionsCaptor.getAllValues().get(1).getTemperature()).isEqualTo(0.0);
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        verify(spec, times(2)).system(systemCaptor.capture());
        assertThat(systemCaptor.getAllValues().get(1)).contains("language-correction retry");
    }

    /**
     * The defect this fixes: a one-page problem sheet and a fifty-page lecture were asked for the
     * same two or three outcomes, so extraction volume followed the uploader's file packaging rather
     * than the material. Measured on a real corpus, exercise sheets were 14-17% of a course's text
     * and produced 36-47% of its skills.
     */
    @Test
    void scalesTheSkillAllowanceWithTheUnitsSize() {
        assertThat(SessionExtractionService.skillBudget(1_092, 3_000)).isEqualTo(1);
        assertThat(SessionExtractionService.skillBudget(4_763, 3_000)).isEqualTo(2);
        assertThat(SessionExtractionService.skillBudget(8_555, 3_000)).isEqualTo(3);
        assertThat(SessionExtractionService.skillBudget(11_600, 3_000)).isEqualTo(4);
    }

    /**
     * A unit below the target still teaches something. Rounding its allowance to zero would silently
     * drop short material — the real risk being exercise sheets, which carry a course's highest Bloom
     * levels.
     */
    @Test
    void neverRoundsAUnitsAllowanceBelowOneSkill() {
        assertThat(SessionExtractionService.skillBudget(1, 3_000)).isEqualTo(1);
        assertThat(SessionExtractionService.skillBudget(0, 3_000)).isEqualTo(1);
    }

    /** However long a unit is, the ceiling still holds: units are split before they reach here. */
    @Test
    void capsTheAllowanceAtTheSessionMaximum() {
        assertThat(SessionExtractionService.skillBudget(250_000, 3_000))
                .isEqualTo(SessionExtractionService.MAX_SKILLS_PER_SESSION);
    }

    /** Zero disables scaling, so the allowance falls back to the flat ceiling. */
    @Test
    void keepsTheFlatCeilingWhenScalingIsDisabled() {
        assertThat(SessionExtractionService.skillBudget(500, 0))
                .isEqualTo(SessionExtractionService.MAX_SKILLS_PER_SESSION);
    }

    /**
     * The template takes four arguments and the call site passes four. Java silently ignores extra
     * arguments to {@code formatted}, so a mismatch does not fail here — it shifts every value one
     * place left and ships a prompt whose "session title" is a stray parameter and whose source text
     * is missing altogether. Only a render can catch that.
     */
    @Test
    void rendersEveryPlaceholderFromTheArgumentsTheCallSitePasses() {
        String prompt = SessionExtractionService.PROMPT_TEMPLATE.formatted(
                "German", 3, "Vorlesung 3", "0: Bayes");

        assertThat(prompt)
                .contains("Write every generated text and shortLabel value in German")
                .contains("Return at most 3.")
                .contains("Vorlesung 3")
                .contains("0: Bayes")
                .doesNotContain("%s")
                .doesNotContain("%d");
    }

    /** The tier contract the pipeline now rests on has to be stated to the model that must honour it. */
    @Test
    void statesTheLevelEachTierMustCarry() {
        String prompt = SessionExtractionService.PROMPT_TEMPLATE.formatted(
                "English", 2, "Lecture 3", "0: Bayes");

        assertThat(prompt)
                .contains("APPLY, ANALYZE, EVALUATE")
                .contains("Knowledge sits at REMEMBER or UNDERSTAND")
                .contains("Return an empty list");
    }

    /** A response above the unit's own allowance is rejected, not just one above the global cap. */
    @Test
    void rejectsMoreSkillsThanTheUnitsAllowancePermits() {
        List<ExtractedSkill> two = List.of(
                new ExtractedSkill("Anwenden von Wegintegralen auf geschlossene Kurven.",
                        "Wegintegrale anwenden", GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, List.of()),
                new ExtractedSkill("Berechnen von Residuen einfacher Polstellen.",
                        "Residuen berechnen", GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, List.of()));

        assertThatThrownBy(() -> SessionExtractionService.validate(two, "German", null, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain more than 1 broad skills");
        assertThat(SessionExtractionService.validate(two, "German", null, 0, 2)).hasSize(2);
    }

    /**
     * The failure this prevents: a real 32-document run died after both attempts on "invalid source
     * range [20..166]: spans more than 5 numbered lines". Every skill in that session cited badly, so
     * the salvage returned nothing and all 39 units of the course were lost to one lecture's
     * footnotes.
     */
    @Test
    void keepsAWellWordedSkillWhoseCitationCannotBeVerified() {
        // Real lines, drawn far too wide: the failing run cited [20..166] where 5 lines are allowed.
        String session = java.util.stream.IntStream.rangeClosed(1, 200)
                .mapToObj(i -> "line " + i).collect(java.util.stream.Collectors.joining("\n"));
        List<ExtractedSkill> overWideRange = List.of(new ExtractedSkill(
                "Applying quadrature rules in higher dimensions.", "Apply Quadrature",
                GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 20, 166, List.of()));

        List<ExtractedSkill> salvaged = SessionExtractionService.salvageValidOutcomes(
                overWideRange, "English", NumberedLines.of(session), 0);

        assertThat(salvaged).singleElement()
                .satisfies(skill -> {
                    assertThat(skill.text()).isEqualTo("Applying quadrature rules in higher dimensions.");
                    // The citation is dropped, never narrowed: five of the 166 lines the model pointed
                    // at would be a citation nobody verified.
                    assertThat(skill.sourceStartLine()).isNull();
                    assertThat(skill.sourceEndLine()).isNull();
                    assertThat(skill.sourceFigure()).isNull();
                });
    }

    /**
     * The failure this prevents: on a course run with figures enabled, two sessions died on "Every
     * skill must cite lines or a figure, never both" after both attempts. What a slide teaches is in
     * the picture, so the model names the picture and the line that captions it — and the run aborted
     * over an outcome that had named its source twice rather than not at all.
     */
    @Test
    void keepsASkillCitingBothLinesAndAFigure() {
        List<ExtractedSkill> citesBoth = List.of(new ExtractedSkill(
                "Applying quadrature rules in higher dimensions.", "Apply Quadrature",
                GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 1, 0, List.of()));

        List<ExtractedSkill> validated = SessionExtractionService.validate(
                citesBoth, "English", NumberedLines.of("one\ntwo\nthree"), 1, 4);

        // Text first, the same precedence the source resolver applies: the range stands and the
        // redundant figure is dropped.
        assertThat(validated).singleElement()
                .satisfies(skill -> {
                    assertThat(skill.sourceStartLine()).isEqualTo(0);
                    assertThat(skill.sourceEndLine()).isEqualTo(1);
                    assertThat(skill.sourceFigure()).isNull();
                });
    }

    /** With both cited and the range unusable, the figure is the half that points at real material. */
    @Test
    void keepsTheFigureWhenTheLineRangeCitedBesideItIsUnusable() {
        String session = java.util.stream.IntStream.rangeClosed(1, 200)
                .mapToObj(i -> "line " + i).collect(java.util.stream.Collectors.joining("\n"));
        List<ExtractedSkill> citesBoth = List.of(new ExtractedSkill(
                "Applying quadrature rules in higher dimensions.", "Apply Quadrature",
                GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 20, 166, 0, List.of()));

        assertThat(SessionExtractionService.validate(citesBoth, "English", NumberedLines.of(session), 1, 4))
                .singleElement()
                .satisfies(skill -> {
                    assertThat(skill.sourceStartLine()).isNull();
                    assertThat(skill.sourceEndLine()).isNull();
                    assertThat(skill.sourceFigure()).isEqualTo(0);
                });
    }

    /** Citing a figure that was never offered is still citing nothing. */
    @Test
    void stillRejectsAFigureThatWasNeverOffered() {
        List<ExtractedSkill> citesMissingFigure = List.of(new ExtractedSkill(
                "Applying quadrature rules in higher dimensions.", "Apply Quadrature",
                GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, null, null, 3, List.of()));

        assertThatThrownBy(() -> SessionExtractionService.validate(
                citesMissingFigure, "English", NumberedLines.of("one\ntwo"), 1, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cites figure 3");
    }

    /**
     * The abort itself: every skill in the session cited both, so before this the salvage returned
     * nothing, the session failed, and the whole course run was aborted.
     */
    @Test
    void salvagesASessionWhereEverySkillCitedBoth() {
        List<ExtractedSkill> citesBoth = List.of(
                new ExtractedSkill("Applying quadrature rules in higher dimensions.", "Apply Quadrature",
                        GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 1, 0, List.of()),
                new ExtractedSkill("Comparing sampling schemes on their convergence.", "Compare Sampling",
                        GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 1, 2, 0, List.of()));

        assertThat(SessionExtractionService.salvageValidOutcomes(
                citesBoth, "English", NumberedLines.of("one\ntwo\nthree"), 1))
                .hasSize(2)
                .allSatisfy(skill -> assertThat(skill.sourceFigure()).isNull());
    }

    /** A badly WORDED skill is still dropped: it is not a usable outcome, however it cites itself. */
    @Test
    void stillDropsASkillWhoseWordingIsInvalid() {
        List<ExtractedSkill> badWording = List.of(
                new ExtractedSkill(null, "No text at all", GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 1, 2, List.of()),
                new ExtractedSkill("Applying quadrature rules in higher dimensions.", "Apply Quadrature",
                        GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 1, 2, List.of()));

        assertThat(SessionExtractionService.salvageValidOutcomes(
                badWording, "English", NumberedLines.of("one\ntwo\nthree"), 0))
                .singleElement()
                .extracting(ExtractedSkill::shortLabel).isEqualTo("Apply Quadrature");
    }

    /** A verifiable citation is kept exactly as the model gave it. */
    @Test
    void keepsAValidCitationUntouched() {
        List<ExtractedSkill> good = List.of(new ExtractedSkill(
                "Applying quadrature rules in higher dimensions.", "Apply Quadrature",
                GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 1, 2, List.of()));

        assertThat(SessionExtractionService.salvageValidOutcomes(
                good, "English", NumberedLines.of("one\ntwo\nthree"), 0))
                .singleElement()
                .satisfies(skill -> {
                    assertThat(skill.sourceStartLine()).isEqualTo(1);
                    assertThat(skill.sourceEndLine()).isEqualTo(2);
                });
    }

    /** Past the end of the text is not imprecision — it points nowhere, so the skill is still dropped. */
    @Test
    void stillDropsASkillWhoseCitationIsBeyondTheText() {
        List<ExtractedSkill> outOfBounds = List.of(new ExtractedSkill(
                "Applying quadrature rules in higher dimensions.", "Apply Quadrature",
                GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, 9, 9, List.of()));

        assertThat(SessionExtractionService.salvageValidOutcomes(
                outOfBounds, "English", NumberedLines.of("one\ntwo\nthree"), 0)).isEmpty();
    }
}
