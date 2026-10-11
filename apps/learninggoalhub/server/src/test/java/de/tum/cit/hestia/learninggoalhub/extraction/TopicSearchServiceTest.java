package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentContentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSection;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSectionRepository;
import de.tum.cit.hestia.learninggoalhub.document.HighlightGeometryService;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionRepository;
import de.tum.cit.hestia.learninggoalhub.extraction.TopicSearchService.PagePlan;
import de.tum.cit.hestia.learninggoalhub.extraction.TopicSearchService.PageRange;
import de.tum.cit.hestia.learninggoalhub.extraction.TopicSearchService.PageRun;
import de.tum.cit.hestia.learninggoalhub.extraction.TopicSearchService.ProposalResponse;
import de.tum.cit.hestia.learninggoalhub.extraction.TopicSearchService.ProposedSubSkill;
import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSource;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TopicSearchServiceTest {

    private static final long COURSE_ID = 1L;

    private final CourseRepository courseRepository = mock(CourseRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentSectionRepository sectionRepository = mock(DocumentSectionRepository.class);
    private final LearningGoalRepository goalRepository = mock(LearningGoalRepository.class);
    private final GoalSourceRepository goalSourceRepository = mock(GoalSourceRepository.class);
    private final SessionExtractionService sessionExtractionService = mock(SessionExtractionService.class);
    private final TopicSearchSynthesizer searchSynthesizer = mock(TopicSearchSynthesizer.class);
    private final TopicTreeSynthesizer treeSynthesizer = mock(TopicTreeSynthesizer.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-16T10:00:00Z"));

    private TopicSearchService service(int maxPages) {
        when(courseRepository.findById(COURSE_ID)).thenReturn(Optional.of(new Course("Machine Learning")));
        UnitExtractor unitExtractor = new UnitExtractor(sessionExtractionService, mock(DocumentContentRepository.class),
                mock(HighlightGeometryService.class), goalSourceRepository, 12_000, 3_000, 3_000);
        return new TopicSearchService(courseRepository, documentRepository, sectionRepository,
                mock(PageDescriptionRepository.class), goalRepository, goalSourceRepository,
                mock(GoalRelationshipRepository.class), mock(HierarchyNodeRepository.class), unitExtractor,
                searchSynthesizer, treeSynthesizer, mock(TaxonomyService.class), 1, maxPages, clock);
    }

    /** A document whose pages are the given texts, each ending in a line break. */
    private static Document document(long id, String filename, String... pages) {
        StringBuilder text = new StringBuilder();
        int[] offsets = new int[pages.length + 1];
        for (int page = 0; page < pages.length; page++) {
            text.append(pages[page]).append('\n');
            offsets[page + 1] = text.length();
        }
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(id);
        when(document.getFilename()).thenReturn(filename);
        when(document.getRawText()).thenReturn(text.toString());
        when(document.getPageOffsets()).thenReturn(offsets);
        return document;
    }

    @Test
    void hitsMergeAcrossAOnePageGapAndSinglePagesAreDropped() {
        assertThat(TopicSearchService.mergeRuns(List.of(3, 4, 6, 9, 12, 13)))
                .containsExactly(new int[]{3, 6}, new int[]{12, 13});
        assertThat(TopicSearchService.mergeRuns(List.of(5))).isEmpty();
    }

    @Test
    void aTermMatchesOnlyWhereAWordStarts() {
        assertThat(TopicSearchService.termPattern("rf").matcher("rf trains many trees").find()).isTrue();
        assertThat(TopicSearchService.termPattern("rf").matcher("(rf) and bagging").find()).isTrue();
        assertThat(TopicSearchService.termPattern("rf").matcher("the model's performance").find()).isFalse();
        assertThat(TopicSearchService.termPattern("random forest").matcher("random forests").find()).isTrue();
        assertThat(TopicSearchService.termPattern("kapital").matcher("die kapitalstruktur").find()).isTrue();
    }

    @Test
    void runsAreLabelledByBookmarkOrFileNameAndTheTopicIsSearchedWhenTermsFail() {
        Document bookmarked = document(41L, "lecture.pdf",
                "Introduction", "Random forest basics", "Bagging", "Random\nForest out-of-bag", "Other", "random forest");
        Document plain = document(42L, "notes.pdf", "Random forests", "Random forest tuning", "Kernels");
        when(documentRepository.findByCourseId(COURSE_ID)).thenReturn(List.of(bookmarked, plain));
        when(sectionRepository.findByDocumentIdOrderByOrdinal(41L)).thenReturn(List.of(
                new DocumentSection(bookmarked, 0, "Ensembles", 0, 10, 2, 6)));
        when(sectionRepository.findByDocumentIdOrderByOrdinal(42L)).thenReturn(List.of());
        when(searchSynthesizer.searchTerms(anyString(), anyString(), nullable(String.class)))
                .thenThrow(new IllegalStateException("model down"));

        PagePlan plan = service(80).planPages(COURSE_ID, "Random forest", null);

        assertThat(plan.terms()).containsExactly("Random forest");
        assertThat(plan.runs()).containsExactly(
                new PageRun(41L, "Ensembles", 2, 6),
                new PageRun(42L, "notes.pdf", 1, 2));
        assertThat(plan.totalPages()).isEqualTo(7);
        assertThat(plan.maxPages()).isEqualTo(80);
    }

    @Test
    void aRunIsLabelledByTheSectionCoveringMostOfItsPages() {
        Document lecture = document(41L, "lecture.pdf", "a", "b", "c", "d", "e", "f");
        List<DocumentSection> sections = List.of(
                new DocumentSection(lecture, 0, "Trees", 0, 1, 1, 2),
                new DocumentSection(lecture, 1, "Random forests", 1, 2, 3, 6));

        assertThat(TopicSearchService.rangeLabel(lecture, sections, 2, 6)).isEqualTo("Random forests");
        assertThat(TopicSearchService.rangeLabel(lecture, List.of(), 2, 6)).isEqualTo("lecture.pdf");
    }

    @Test
    void generatedTermsWidenTheSearch() {
        Document lecture = document(41L, "lecture.pdf", "Intro", "Bagging", "Bootstrap samples", "Other");
        when(documentRepository.findByCourseId(COURSE_ID)).thenReturn(List.of(lecture));
        when(searchSynthesizer.searchTerms(anyString(), anyString(), nullable(String.class)))
                .thenReturn(List.of("bagging", "Bootstrap"));

        PagePlan plan = service(80).planPages(COURSE_ID, "Random forest", null);

        assertThat(plan.terms()).containsExactly("Random forest", "bagging", "Bootstrap");
        assertThat(plan.runs()).containsExactly(new PageRun(41L, "lecture.pdf", 2, 3));
    }

    @Test
    void scatteredSinglePagesAreOfferedWhenNoRunFormed() {
        Document lecture = document(41L, "lecture.pdf", "Buybacks", "Other", "Other", "Buybacks again", "Other");
        when(documentRepository.findByCourseId(COURSE_ID)).thenReturn(List.of(lecture));
        when(searchSynthesizer.searchTerms(anyString(), anyString(), nullable(String.class))).thenReturn(List.of());

        PagePlan plan = service(80).planPages(COURSE_ID, "Buybacks", null);

        assertThat(plan.runs()).containsExactly(
                new PageRun(41L, "lecture.pdf", 1, 1), new PageRun(41L, "lecture.pdf", 4, 4));
    }

    @Test
    void rangesAboveTheCapAreRejected() {
        Document lecture = document(41L, "lecture.pdf", "a", "b", "c", "d", "e", "f");
        when(documentRepository.findByCourseId(COURSE_ID)).thenReturn(List.of(lecture));

        assertThatThrownBy(() -> service(5).search(COURSE_ID, "Random forest",
                List.of(new PageRange(41L, 1, 6)), null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void existingSubSkillsOnThePagesAreMarkedAndFewSubSkillsAreNotStructured() {
        Document lecture = document(41L, "lecture.pdf", "Bagging trains trees on bootstrap samples",
                "Out-of-bag error estimates generalisation");
        when(documentRepository.findByCourseId(COURSE_ID)).thenReturn(List.of(lecture));
        when(sessionExtractionService.extract(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill("Explain bagging.", 0), skill("Estimate the out-of-bag error.", 1)));
        LearningGoal existing = mock(LearningGoal.class);
        when(existing.getId()).thenReturn(90L);
        when(existing.getText()).thenReturn("Explain how bagging works.");
        when(existing.getRole()).thenReturn(GoalRole.SKILL);
        when(goalRepository.findByCourseIdAndOriginIn(eq(COURSE_ID), any())).thenReturn(List.of(existing));
        GoalSource source = mock(GoalSource.class);
        when(source.getPage()).thenReturn(1);
        when(source.getDocument()).thenReturn(lecture);
        when(source.getGoal()).thenReturn(existing);
        when(goalSourceRepository.findByGoalIdIn(any())).thenReturn(List.of(source));
        when(searchSynthesizer.duplicates(anyList(), eq(List.of("Explain how bagging works.")), nullable(String.class)))
                .thenReturn(List.of(0, -1));

        ProposalResponse proposal = service(80).search(COURSE_ID, "Random forest",
                List.of(new PageRange(41L, 1, 2)), null);

        assertThat(proposal.pagesRead()).isEqualTo(2);
        assertThat(proposal.skills()).isEmpty();
        assertThat(proposal.direct()).extracting(ProposedSubSkill::text).containsExactly("Estimate the out-of-bag error.");
        assertThat(proposal.direct().getFirst().source().page()).isEqualTo(2);
        assertThat(proposal.direct().getFirst().source().snippet()).isEqualTo("Out-of-bag error estimates generalisation");
        assertThat(proposal.direct().getFirst().knowledge()).extracting(TopicSearchService.ProposedKnowledge::text)
                .containsExactly("Recall what a bootstrap sample is.");
        assertThat(proposal.duplicates()).singleElement().satisfies(duplicate -> {
            assertThat(duplicate.text()).isEqualTo("Explain bagging.");
            assertThat(duplicate.duplicateOf().goalId()).isEqualTo(90L);
        });
        verify(treeSynthesizer, never()).structure(anyString(), anyList(), anyString(), nullable(String.class));
    }

    @Test
    void fourOrMoreSubSkillsAreStructuredIntoSkills() {
        Document lecture = document(41L, "lecture.pdf", "one", "two", "three", "four");
        when(documentRepository.findByCourseId(COURSE_ID)).thenReturn(List.of(lecture));
        when(sessionExtractionService.extract(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill("A.", 0), skill("B.", 1), skill("C.", 2), skill("D.", 3)));
        when(goalRepository.findByCourseIdAndOriginIn(eq(COURSE_ID), any())).thenReturn(List.of());
        when(treeSynthesizer.structure(eq("Random forest"), eq(List.of("A.", "B.", "C.", "D.")), anyString(),
                nullable(String.class)))
                .thenReturn(new TopicTreeSynthesizer.PlannedTopic("Random forest", List.of(
                        new TopicTreeSynthesizer.PlannedCapability("Build a forest.", List.of(0, 2))), List.of(1, 3)));
        when(treeSynthesizer.shortLabels(eq(List.of("Build a forest.")), anyString(), nullable(String.class)))
                .thenReturn(List.of("Build forests"));

        ProposalResponse proposal = service(80).search(COURSE_ID, "Random forest",
                List.of(new PageRange(41L, 1, 4)), null);

        assertThat(proposal.skills()).singleElement().satisfies(skill -> {
            assertThat(skill.text()).isEqualTo("Build a forest.");
            assertThat(skill.shortLabel()).isEqualTo("Build forests");
            // Classification is mocked away, so the levels are the members' floor.
            assertThat(skill.bloom()).isEqualTo(BloomLevel.APPLY);
            assertThat(skill.subSkills()).extracting(ProposedSubSkill::text).containsExactly("A.", "C.");
        });
        assertThat(proposal.direct()).extracting(ProposedSubSkill::text).containsExactly("B.", "D.");
    }

    @Test
    void anExpiredProposalCannotBeAccepted() {
        Document lecture = document(41L, "lecture.pdf", "one", "two");
        when(documentRepository.findByCourseId(COURSE_ID)).thenReturn(List.of(lecture));
        when(sessionExtractionService.extract(anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill("A.", 0)));
        when(goalRepository.findByCourseIdAndOriginIn(eq(COURSE_ID), any())).thenReturn(List.of());
        TopicSearchService service = service(80);
        ProposalResponse proposal = service.search(COURSE_ID, "Random forest", List.of(new PageRange(41L, 1, 2)), null);

        clock.advance(TopicSearchService.PROPOSAL_TTL.plus(Duration.ofSeconds(1)));

        assertThatThrownBy(() -> service.accept(COURSE_ID, proposal.proposalId(), List.of("s0")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** A skill quoting line {@code line} of its unit, with one knowledge item. */
    private static ExtractedSkill skill(String text, int line) {
        return new ExtractedSkill(text, null, GoalKind.EXPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, line, line,
                List.of(new ExtractedSkill.Knowledge("Recall what a bootstrap sample is.", null, GoalKind.EXPLICIT,
                        BloomLevel.REMEMBER, SoloLevel.UNISTRUCTURAL, line, line)));
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
