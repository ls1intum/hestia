package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContent;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSectionRepository;
import de.tum.cit.hestia.learninggoalhub.document.HighlightGeometryService;
import de.tum.cit.hestia.learninggoalhub.document.LanguageDetectionService;
import de.tum.cit.hestia.learninggoalhub.document.PageDescription;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionRepository;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import de.tum.cit.hestia.learninggoalhub.embedding.EmbeddingService;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.StructuredOutputConverter;

class ExtractionRunnerFigureSettingTest {

    @Test
    void disabledFiguresSkipDescriptionAndKeepEvidenceOutOfExtractionPrompt() {
        Fixture fixture = fixture(false);

        fixture.runner().runForCourse(1L);

        verify(fixture.pageDescriptionService(), never()).describeEligiblePages(any(), any(), anyString(), anyString());
        verify(fixture.pageDescriptionRepository(), never()).findByDocumentId(any());
        verifyPromptDoesNotContainFigures(fixture.chatClient());
        verifyRunParams(fixture.auditService(), false);
    }

    @Test
    void enabledFiguresDescribePagesAndPassEvidenceToExtractionPrompt() {
        Fixture fixture = fixture(true);

        fixture.runner().runForCourse(1L);

        verify(fixture.pageDescriptionService()).describeEligiblePages(any(), any(), eq("en"), eq("English"));
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(fixture.chatClient().prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Figure descriptions")
                .contains("A process diagram.");
        verifyRunParams(fixture.auditService(), true);
    }

    private static void verifyPromptDoesNotContainFigures(ChatClient chatClient) {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClient.prompt()).user(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).doesNotContain("Figure descriptions");
    }

    private static void verifyRunParams(ExtractionRunAuditService auditService, boolean figuresEnabled) {
        ArgumentCaptor<String> paramsCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditService).start(eq(1L), isNull(), eq(SessionExtractionService.PROMPT_VERSION),
                paramsCaptor.capture());
        assertThat(paramsCaptor.getValue())
                .contains("\"figures-enabled\":" + figuresEnabled)
                .contains("\"figure-prompt-version\":\"" + PageDescriptionService.FIGURE_PROMPT_VERSION + "\"");
    }

    private static Fixture fixture(boolean figuresEnabled) {
        CourseRepository courseRepository = mock(CourseRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentContentRepository documentContentRepository = mock(DocumentContentRepository.class);
        PageDescriptionService pageDescriptionService = mock(PageDescriptionService.class);
        PageDescriptionRepository pageDescriptionRepository = mock(PageDescriptionRepository.class);
        LearningGoalRepository goalRepository = mock(LearningGoalRepository.class);
        GoalSourceRepository goalSourceRepository = mock(GoalSourceRepository.class);
        GoalRelationshipRepository goalRelationshipRepository = mock(GoalRelationshipRepository.class);
        ExtractionService extractionService = mock(ExtractionService.class);
        SessionGoalConsolidator sessionGoalConsolidator = mock(SessionGoalConsolidator.class);
        ExtractionRunAuditService auditService = mock(ExtractionRunAuditService.class);
        GoalCandidateRepository goalCandidateRepository = mock(GoalCandidateRepository.class);
        DocumentSectionRepository documentSectionRepository = mock(DocumentSectionRepository.class);
        TerminalCompetencySynthesizer terminalCompetencySynthesizer = mock(TerminalCompetencySynthesizer.class);
        CompetencyAssignmentSynthesizer competencyAssignmentSynthesizer = mock(CompetencyAssignmentSynthesizer.class);
        DocumentChunker documentChunker = mock(DocumentChunker.class);
        HierarchyNodeRepository hierarchyNodeRepository = mock(HierarchyNodeRepository.class);
        TaxonomyService taxonomyService = mock(TaxonomyService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        HighlightGeometryService highlightGeometryService = mock(HighlightGeometryService.class);
        ExtractionProgressTracker progressTracker = new ExtractionProgressTracker();

        Course course = mock(Course.class);
        when(course.getId()).thenReturn(1L);
        when(course.getName()).thenReturn("Figure course");
        when(course.isFiguresEnabled()).thenReturn(figuresEnabled);
        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));

        String text = "text from the lecture";
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(42L);
        when(document.getFilename()).thenReturn("lecture.pdf");
        when(document.getRawText()).thenReturn(text);
        when(document.getPageOffsets()).thenReturn(new int[]{0, text.length()});
        when(documentRepository.findByCourseId(1L)).thenReturn(List.of(document));

        when(goalRepository.findByCourseIdAndOriginIn(eq(1L), any())).thenReturn(List.of());
        when(goalRepository.findByCourseIdAndHierarchyNodeIsNotNull(1L)).thenReturn(List.of());
        when(hierarchyNodeRepository.existsByCourseIdAndLevel(eq(1L), any())).thenReturn(false);
        when(hierarchyNodeRepository.findByCourseId(1L)).thenReturn(List.of());
        when(hierarchyNodeRepository.save(any(HierarchyNode.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(documentSectionRepository.findByDocumentIdOrderByOrdinal(42L)).thenReturn(List.of());
        when(documentChunker.getChunkSize()).thenReturn(8000);
        when(auditService.start(eq(1L), nullable(String.class), anyString(), anyString())).thenReturn(1L);

        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        // system() and options() return the spec itself, so the chain is one mock and a verify()
        // does not have to rebuild it — rebuilding would be recorded as another user() call.
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());
        clearInvocations(spec);
        SessionExtractionService sessionExtractionService = new SessionExtractionService(
                chatClientBuilder, mock(LanguageDetectionService.class), 0.2);

        PageDescription pageDescription = mock(PageDescription.class);
        when(pageDescription.getPage()).thenReturn(1);
        when(pageDescription.getDescription()).thenReturn("A process diagram.");
        when(pageDescription.isTeachesContent()).thenReturn(true);
        when(pageDescriptionRepository.findByDocumentId(42L)).thenReturn(List.of(pageDescription));

        if (figuresEnabled) {
            DocumentContent documentContent = mock(DocumentContent.class);
            when(documentContent.getBytes()).thenReturn(new byte[]{1, 2, 3});
            when(documentContentRepository.findById(42L)).thenReturn(Optional.of(documentContent));
        }

        ExtractionRunner runner = new ExtractionRunner(
                courseRepository,
                documentRepository,
                documentContentRepository,
                pageDescriptionService,
                pageDescriptionRepository,
                goalRepository,
                goalSourceRepository,
                goalRelationshipRepository,
                extractionService,
                sessionExtractionService,
                sessionGoalConsolidator,
                auditService,
                goalCandidateRepository,
                documentSectionRepository,
                terminalCompetencySynthesizer,
                competencyAssignmentSynthesizer,
                documentChunker,
                hierarchyNodeRepository,
                taxonomyService,
                embeddingService,
                progressTracker,
                1,
                1,
                80_000,
                null,
                20,
                64,
                highlightGeometryService);
        return new Fixture(runner, pageDescriptionService, pageDescriptionRepository, chatClient, auditService);
    }

    private record Fixture(ExtractionRunner runner, PageDescriptionService pageDescriptionService,
                            PageDescriptionRepository pageDescriptionRepository, ChatClient chatClient,
                            ExtractionRunAuditService auditService) {
    }
}
