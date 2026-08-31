package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSectionRepository;
import de.tum.cit.hestia.learninggoalhub.document.HighlightGeometryService;
import de.tum.cit.hestia.learninggoalhub.document.LanguageDetectionService;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionRepository;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.StructuredOutputConverter;

/**
 * A model reply that cannot be parsed used to abort the whole course. Each session now absorbs its
 * own failure so the rest of the run still produces outcomes.
 */
class ExtractionRunnerSessionIsolationTest {

    @Test
    void oneFailedSessionLeavesTheRestOfTheRunIntact() {
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

        assertThatCode(() -> runner.runForCourse(1L)).doesNotThrowAnyException();

        verify(spec, times(2)).user(anyString());
        // Both places the drop has to show up: the review screen polls the snapshot, the audit row
        // is what still says so after the tracker has forgotten the run.
        assertThat(progressTracker.snapshot(1L).orElseThrow().failedSessions()).isEqualTo(1);
        verify(auditService).finish(eq(1L), eq(ExtractionRun.Status.SUCCEEDED), isNull(), any(),
                eq(1), anyString());
    }

    private static ExtractionRunner runner(ChatClient chatClient, ExtractionRunAuditService auditService,
                                           ExtractionProgressTracker progressTracker) {
        CourseRepository courseRepository = mock(CourseRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        LearningGoalRepository goalRepository = mock(LearningGoalRepository.class);
        HierarchyNodeRepository hierarchyNodeRepository = mock(HierarchyNodeRepository.class);
        DocumentSectionRepository documentSectionRepository = mock(DocumentSectionRepository.class);
        DocumentChunker documentChunker = mock(DocumentChunker.class);

        Course course = mock(Course.class);
        when(course.getId()).thenReturn(1L);
        when(course.getName()).thenReturn("Maths-heavy course");
        when(course.isFiguresEnabled()).thenReturn(false);
        when(courseRepository.findById(1L)).thenReturn(Optional.of(course));

        // No sections, so each document is exactly one session. Both documents are stubbed before
        // the call below: nesting mock setup inside a when() argument breaks the outer stubbing.
        Document first = document(41L, "erste Vorlesung");
        Document second = document(42L, "zweite Vorlesung");
        when(documentRepository.findByCourseId(1L)).thenReturn(List.of(first, second));
        when(documentSectionRepository.findByDocumentIdOrderByOrdinal(anyLong())).thenReturn(List.of());

        when(goalRepository.findByCourseIdAndOriginIn(eq(1L), any())).thenReturn(List.of());
        when(goalRepository.findByCourseIdAndHierarchyNodeIsNotNull(1L)).thenReturn(List.of());
        when(hierarchyNodeRepository.existsByCourseIdAndLevel(eq(1L), any())).thenReturn(false);
        when(hierarchyNodeRepository.findByCourseId(1L)).thenReturn(List.of());
        when(hierarchyNodeRepository.save(any(HierarchyNode.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(documentChunker.getChunkSize()).thenReturn(8000);
        when(auditService.start(eq(1L), nullable(String.class), anyString(), anyString())).thenReturn(1L);

        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        SessionExtractionService sessionExtractionService = new SessionExtractionService(
                chatClientBuilder, mock(LanguageDetectionService.class), 0.2);

        return new ExtractionRunner(
                courseRepository,
                documentRepository,
                mock(DocumentContentRepository.class),
                mock(PageDescriptionService.class),
                mock(PageDescriptionRepository.class),
                goalRepository,
                mock(GoalSourceRepository.class),
                mock(GoalRelationshipRepository.class),
                mock(ExtractionService.class),
                sessionExtractionService,
                mock(SessionGoalConsolidator.class),
                auditService,
                mock(GoalCandidateRepository.class),
                documentSectionRepository,
                mock(CompactTaxonomySynthesizer.class),
                documentChunker,
                hierarchyNodeRepository,
                mock(TaxonomyService.class),
                progressTracker,
                // Single-threaded, so the failing session is deterministically the first one.
                1,
                1,
                80_000,
                null,
                20,
                mock(HighlightGeometryService.class));
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
