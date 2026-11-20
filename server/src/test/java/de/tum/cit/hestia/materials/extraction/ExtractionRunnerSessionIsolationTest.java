package de.tum.cit.hestia.materials.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.materials.topictree.TopicTreeService;
import de.tum.cit.hestia.materials.course.CourseContext;
import de.tum.cit.hestia.materials.course.MaterialSettingsService;
import de.tum.cit.hestia.materials.course.ExtractionStatus;
import de.tum.cit.hestia.materials.document.Document;
import de.tum.cit.hestia.materials.document.DocumentContentRepository;
import de.tum.cit.hestia.materials.document.DocumentRepository;
import de.tum.cit.hestia.materials.document.DocumentSectionRepository;
import de.tum.cit.hestia.materials.document.HighlightGeometryService;
import de.tum.cit.hestia.materials.document.LanguageDetectionService;
import de.tum.cit.hestia.materials.document.PageDescriptionRepository;
import de.tum.cit.hestia.materials.document.PageDescriptionService;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNode;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.materials.llm.LlmClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.StructuredOutputConverter;

/**
 * A model reply that cannot be parsed must abort the transaction after the other session workers
 * finish, so the application never publishes a plausible-looking partial course.
 */
class ExtractionRunnerSessionIsolationTest {

    private static final java.util.UUID COURSE = java.util.UUID.randomUUID();

    @Test
    void oneFailedSessionFailsTheRunAndNamesTheMissingSession() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        // The first session's reply is unparsable; the second must still be requested.
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenThrow(new RuntimeException("Unrecognized character escape '(' "))
                .thenReturn(List.of());
        clearInvocations(spec);

        ExtractionRunAuditService auditService = mock(ExtractionRunAuditService.class);
        ExtractionProgressTracker progressTracker = new ExtractionProgressTracker();
        ExtractionRunner runner = runner(chatClient, auditService, progressTracker);

        assertThatThrownBy(() -> runner.runForCourse(COURSE, null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("41.pdf")
                .hasMessageContaining("Retry the extraction");

        verify(spec, times(2)).user(anyString());
        ExtractionProgressTracker.Snapshot snapshot = progressTracker.snapshot(COURSE).orElseThrow();
        assertThat(snapshot.failedSessions()).isEqualTo(1);
        assertThat(snapshot.failedSessionNames()).containsExactly("41.pdf");
        verify(auditService).finish(eq(1L), eq(ExtractionStatus.FAILED),
                org.mockito.ArgumentMatchers.contains("41.pdf"), isNull(), eq(1), eq(0), anyString());
    }

    @Test
    void aRunThatFindsNoGoalsFailsInsteadOfReportingSuccess() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        // Every session answers, but none of them names an outcome.
        when(spec.user(anyString()).call().entity(any(StructuredOutputConverter.class)))
                .thenReturn(List.of());

        ExtractionRunAuditService auditService = mock(ExtractionRunAuditService.class);
        ExtractionProgressTracker progressTracker = new ExtractionProgressTracker();
        ExtractionRunner runner = runner(chatClient, auditService, progressTracker);

        assertThatThrownBy(() -> runner.runForCourse(COURSE, null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no learning goals");
        verify(auditService).finish(eq(1L), eq(ExtractionStatus.FAILED),
                org.mockito.ArgumentMatchers.contains("no learning goals"), isNull(), eq(0), eq(0), anyString());
    }

    private static ExtractionRunner runner(ChatClient chatClient, ExtractionRunAuditService auditService,
                                           ExtractionProgressTracker progressTracker) {
        MaterialSettingsService settingsService = mock(MaterialSettingsService.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        de.tum.cit.hestia.materials.goal.PlacedGoals placedGoals = mock(de.tum.cit.hestia.materials.goal.PlacedGoals.class);
        HierarchyNodeRepository hierarchyNodeRepository = mock(HierarchyNodeRepository.class);
        DocumentSectionRepository documentSectionRepository = mock(DocumentSectionRepository.class);

        when(settingsService.context(COURSE))
                .thenReturn(new CourseContext(COURSE, "Maths-heavy course", null, false));

        // No sections, so each document is exactly one session. Both documents are stubbed before
        // the call below: nesting mock setup inside a when() argument breaks the outer stubbing.
        Document first = document(41L, "erste Vorlesung");
        Document second = document(42L, "zweite Vorlesung");
        when(documentRepository.findByCourseId(COURSE)).thenReturn(List.of(first, second));
        when(documentSectionRepository.findByDocumentIdOrderByOrdinal(anyLong())).thenReturn(List.of());

        when(placedGoals.list(COURSE)).thenReturn(List.of());
        when(hierarchyNodeRepository.existsByCourseIdAndLevel(eq(COURSE), any())).thenReturn(false);
        when(hierarchyNodeRepository.findByCourseId(COURSE)).thenReturn(List.of());
        when(hierarchyNodeRepository.save(any(HierarchyNode.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(auditService.start(eq(COURSE), nullable(String.class), anyString(), anyString())).thenReturn(1L);

        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        SessionExtractionService sessionExtractionService = new SessionExtractionService(
                new LlmClient(chatClientBuilder), mock(LanguageDetectionService.class), progressTracker, 0.2);

        return new ExtractionRunner(
                settingsService,
                documentRepository,
                mock(DocumentContentRepository.class),
                mock(PageDescriptionService.class),
                mock(PageDescriptionRepository.class),
                placedGoals,
                mock(de.tum.cit.hestia.core.LearningGoalApi.class),
                new UnitExtractor(sessionExtractionService, mock(DocumentContentRepository.class), mock(HighlightGeometryService.class), placedGoals, 12_000, 3_000, 3_000),
                auditService,
                documentSectionRepository,
                mock(TopicTreeService.class),
                hierarchyNodeRepository,
                progressTracker,
                org.springframework.transaction.support.TransactionOperations.withoutTransaction(),
                // Single-threaded, so the failing session is deterministically the first one.
                1,
                1,
                false,
                null);
    }

    private static Document document(long id, String text) {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(id);
        when(document.getFilename()).thenReturn(id + ".pdf");
        when(document.getRawText()).thenReturn(text);
        when(document.getPageOffsets()).thenReturn(new int[]{0, text.length()});
        return document;
    }
}
