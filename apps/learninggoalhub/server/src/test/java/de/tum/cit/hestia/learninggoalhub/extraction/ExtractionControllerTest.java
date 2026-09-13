package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSection;
import de.tum.cit.hestia.learninggoalhub.document.DocumentSectionRepository;
import de.tum.cit.hestia.learninggoalhub.document.PageDescription;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionRepository;
import de.tum.cit.hestia.learninggoalhub.document.PageDescriptionService;
import de.tum.cit.hestia.learninggoalhub.embedding.EmbeddingService;
import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.EvidenceKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.goal.GoalOrigin;
import de.tum.cit.hestia.learninggoalhub.goal.GoalRole;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSource;
import de.tum.cit.hestia.learninggoalhub.goal.GoalSourceRepository;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoal;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalRepository;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationshipRepository;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "hestia.extraction.direct-max-chars=80")
class ExtractionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentSectionRepository documentSectionRepository;

    @Autowired
    private PageDescriptionRepository pageDescriptionRepository;

    @Autowired
    private LearningGoalRepository goalRepository;

    @Autowired
    private GoalSourceRepository goalSourceRepository;

    @Autowired
    private GoalRelationshipRepository goalRelationshipRepository;

    @Autowired
    private HierarchyNodeRepository hierarchyRepository;

    @Autowired
    private ExtractionRunRepository extractionRunRepository;

    @Autowired
    private ExtractionRunAuditService extractionRunAuditService;

    @MockitoBean
    private SessionExtractionService sessionExtractionService;

    @MockitoBean
    private PageDescriptionService pageDescriptionService;

    @MockitoBean
    private EmbeddingService embeddingService;

    @MockitoBean
    private TaxonomyService taxonomyService;




    @MockitoBean
    private CompactTaxonomySynthesizer compactTaxonomySynthesizer;

    @BeforeEach
    void stubIdentityTreeSynthesis() {
        when(compactTaxonomySynthesizer.synthesize(anyList(), anyString(), any())).thenAnswer(inv -> {
            List<CompactTaxonomySynthesizer.Candidate> candidates = inv.getArgument(0);
            List<Integer> supporting = java.util.stream.IntStream.range(0, candidates.size()).boxed().toList();
            if (supporting.isEmpty()) {
                // Matches production: no seeds means no plan, and no representative to elect.
                return new CompactTaxonomySynthesizer.Plan(List.of(), List.of());
            }
            return new CompactTaxonomySynthesizer.Plan(List.of(new CompactTaxonomySynthesizer.PlannedSkill(
                    "Applying the course capability in representative contexts.",
                    "Apply Course Capability",
                    List.of(new CompactTaxonomySynthesizer.PlannedSubSkill(
                            supporting.getFirst(), supporting)))), List.of());
        });
    }

    private void stubCompactPlan(String skillLabel, List<List<Integer>> supportingGroups) {
        when(compactTaxonomySynthesizer.synthesize(anyList(), anyString(), any())).thenReturn(
                new CompactTaxonomySynthesizer.Plan(List.of(
                        new CompactTaxonomySynthesizer.PlannedSkill(
                                "Applying the planned course capability in representative contexts.", skillLabel,
                                supportingGroups.stream()
                                        .map(group -> new CompactTaxonomySynthesizer.PlannedSubSkill(
                                                group.getFirst(), group))
                                        .toList())),
                        List.of()));
    }

    private static ExtractedSkill skill(ExtractedGoal goal) {
        return skill(goal, 0, 0);
    }

    private static ExtractedSkill skill(ExtractedGoal goal, int sourceStartLine, int sourceEndLine) {
        return new ExtractedSkill(goal.text(), goal.shortLabel(), goal.kind(),
                sourceStartLine, sourceEndLine, List.of());
    }

    private static ExtractedSkill figureSkill(String text, int sourceFigure) {
        return new ExtractedSkill(text, text, GoalKind.IMPLICIT, null, null, sourceFigure, List.of());
    }

    private void startExtraction(Long courseId) throws Exception {
        mockMvc.perform(post("/api/courses/{id}/extract", courseId))
                .andExpect(status().isAccepted());
        awaitSucceeded(courseId);
    }

    private void startForcedExtraction(Long courseId) throws Exception {
        mockMvc.perform(post("/api/courses/{id}/extract?force=true", courseId))
                .andExpect(status().isAccepted());
        awaitSucceeded(courseId);
    }

    /**
     * The run is asynchronous now, so a pipeline exception no longer surfaces as a failed POST — it
     * lands on the tracker as FAILED. Assert the happy path here so a broken run fails loudly with
     * its own error rather than as a confusing empty-result assertion further down the test.
     */
    private void awaitSucceeded(Long courseId) throws Exception {
        String body = awaitExtraction(courseId);
        assertThat(body).as("extraction for course %s should have succeeded", courseId)
                .contains("\"status\":\"SUCCEEDED\"");
    }

    /** For runs the test expects to fail — the guard rejections and audit-failure cases. */
    private void startExtractionExpectingFailure(Long courseId) throws Exception {
        mockMvc.perform(post("/api/courses/{id}/extract", courseId))
                .andExpect(status().isAccepted());
        awaitExtraction(courseId);
    }

    /** Waits for the run to reach a terminal state and returns the final status body. */
    private String awaitExtraction(Long courseId) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            MvcResult result = mockMvc.perform(get("/api/courses/{id}/extract/status", courseId)).andReturn();
            String body = result.getResponse().getContentAsString();
            if (body.contains("\"status\":\"SUCCEEDED\"")
                    || body.contains("\"status\":\"FAILED\"")) {
                return body;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Extraction did not finish for course " + courseId);
    }

    @Test
    void extractionRefusesToReplaceExistingGoalsWithoutForce() throws Exception {
        Course course = courseRepository.save(new Course("Extraction guard"));
        LearningGoal existing = goalRepository.save(new LearningGoal(course, "Existing goal", GoalKind.EXPLICIT));

        startExtractionExpectingFailure(course.getId());

        mockMvc.perform(get("/api/courses/{id}/extract/status", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        verify(sessionExtractionService, never()).extract(anyString(), anyString(), anyString(), anyString(), any());
        assertThat(goalRepository.findById(existing.getId())).isPresent();
    }

    @Test
    void forcedExtractionClearsThePreviousRunBeforeBuildingItAgain() throws Exception {
        Course course = courseRepository.save(new Course("Forced extraction"));
        Document document = documentRepository.save(
                new Document(course, "forced.pdf", "application/pdf", "new outcome"));
        HierarchyNode oldModule = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Old module"));
        HierarchyNode oldSession = hierarchyRepository.save(
                new HierarchyNode(course, oldModule, HierarchyLevel.SESSION, "Old session", document));
        LearningGoal oldGoal = new LearningGoal(course, "Old goal", GoalKind.EXPLICIT);
        oldGoal.setHierarchyNode(oldSession);
        oldGoal = goalRepository.saveAndFlush(oldGoal);
        goalSourceRepository.save(new GoalSource(oldGoal, document, "old source"));

        when(sessionExtractionService.extract(eq("forced.pdf"), eq("new outcome"), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("New goal", "New goal", GoalKind.EXPLICIT, "new outcome"))));

        startForcedExtraction(course.getId());

        assertThat(goalRepository.findById(oldGoal.getId())).isEmpty();
        assertThat(goalSourceRepository.findByGoalId(oldGoal.getId())).isEmpty();
        assertThat(hierarchyRepository.findByCourseId(course.getId()))
                .noneMatch(node -> node.getLabel().equals("Old module") || node.getLabel().equals("Old session"));
        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(goal -> goal.getOrigin() == GoalOrigin.EXTRACTED)
                .extracting(LearningGoal::getText)
                .containsExactly("New goal");
    }

    @Test
    void extractionPersistsGoalsAndSourcesPerDocument() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture text about TDD"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise on refactoring"));

        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture text about TDD"), eq("en"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Apply test-driven development.", "Test-Driven Development", GoalKind.EXPLICIT,
                        "...write a failing test first..."), 99, 99),
                skill(new ExtractedGoal("Value short feedback loops.", "Feedback Loops", GoalKind.IMPLICIT,
                        "...keep tests fast..."))
        ));
        when(sessionExtractionService.extract(eq("exercise.pdf"), eq("exercise on refactoring"), eq("en"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Refactor without changing behaviour.", "Behaviour-Preserving Refactoring",
                        GoalKind.EXPLICIT, "...extract method..."))
        ));
        stubEmbedAll(Map.of(
                "Apply test-driven development.", orthogonalEmbedding(0),
                "Value short feedback loops.", orthogonalEmbedding(1),
                "Refactor without changing behaviour.", orthogonalEmbedding(2)));

        startExtraction(course.getId());

        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId()).stream()
                .filter(goal -> goal.getOrigin() == GoalOrigin.EXTRACTED)
                .toList();
        assertThat(goals).hasSize(3);
        assertThat(goals).extracting(LearningGoal::getKind)
                .containsExactlyInAnyOrder(GoalKind.EXPLICIT, GoalKind.IMPLICIT, GoalKind.EXPLICIT);
        assertThat(goals).extracting(LearningGoal::getShortLabel)
                .containsExactlyInAnyOrder("Test-Driven Development", "Feedback Loops",
                        "Behaviour-Preserving Refactoring");
        assertThat(goals).allSatisfy(g -> assertThat(g.getEmbedding()).isNull());
        verifyNoInteractions(embeddingService);

        long sourcesForLecture = goalSourceRepository.findAll().stream()
                .filter(s -> s.getDocument().getId().equals(lecture.getId()))
                .count();
        long sourcesForExercise = goalSourceRepository.findAll().stream()
                .filter(s -> s.getDocument().getId().equals(exercise.getId()))
                .count();
        assertThat(sourcesForLecture).isEqualTo(2);
        assertThat(sourcesForExercise).isEqualTo(1);

        LearningGoal fabricatedGoal = goals.stream()
                .filter(g -> g.getText().equals("Apply test-driven development."))
                .findFirst()
                .orElseThrow();
        assertThat(goalSourceRepository.findByGoalId(fabricatedGoal.getId()))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.getSnippet()).isEmpty();
                    assertThat(source.getPage()).isNull();
                    assertThat(source.getGroundingQuality()).isEqualTo(SourceMatchQuality.NONE);
                    assertThat(source.isGrounded()).isFalse();
                    assertThat(source.getUnverifiedSnippet()).isNull();
                });

        LearningGoal exactGoal = goals.stream()
                .filter(g -> g.getText().equals("Value short feedback loops."))
                .findFirst()
                .orElseThrow();
        assertThat(goalSourceRepository.findByGoalId(exactGoal.getId()))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.getSnippet()).isEqualTo("lecture text about TDD");
                    assertThat(source.getGroundingQuality()).isEqualTo(SourceMatchQuality.EXACT_IN_SESSION);
                    assertThat(source.isGrounded()).isTrue();
                });

        ExtractionRun run = extractionRunRepository.findByCourseId(course.getId()).stream()
                .findFirst()
                .orElseThrow();
        assertThat(run.getStatus()).isEqualTo(ExtractionRun.Status.SUCCEEDED);
        assertThat(run.getPromptVersion()).isEqualTo(SessionExtractionService.PROMPT_VERSION);
        assertThat(run.getGoalsCreated()).isEqualTo(3);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getParams()).contains("direct-max-chars");
    }

    @Test
    void multiPdfExtractionUsesNaturalLectureOrderInsteadOfUploadOrder() throws Exception {
        Course course = courseRepository.save(new Course("Naturally ordered lectures"));
        documentRepository.save(new Document(course, "Lecture 10.pdf", "application/pdf", "later material"));
        documentRepository.save(new Document(course, "Lecture 2.pdf", "application/pdf", "earlier material"));
        when(sessionExtractionService.extract(eq("Lecture 10.pdf"), eq("later material"),
                eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal(
                        "Applying the later method in representative contexts.", "Apply Later Method",
                        GoalKind.IMPLICIT, "later material"))));
        when(sessionExtractionService.extract(eq("Lecture 2.pdf"), eq("earlier material"),
                eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal(
                        "Applying the earlier method in representative contexts.", "Apply Earlier Method",
                        GoalKind.IMPLICIT, "earlier material"))));

        startExtraction(course.getId());

        Map<String, Integer> orderByLabel = goalRepository.findByCourseId(course.getId()).stream()
                .filter(goal -> goal.getOrigin() == GoalOrigin.EXTRACTED)
                .collect(Collectors.toMap(LearningGoal::getShortLabel, LearningGoal::getLectureOrder));
        assertThat(orderByLabel.get("Apply Earlier Method"))
                .isLessThan(orderByLabel.get("Apply Later Method"));
    }

    @Test
    void auditServicePersistsRunningThenSucceededInSeparateTransactions() {
        Course course = courseRepository.save(new Course("Audit lifecycle"));

        Long runId = extractionRunAuditService.start(course.getId(), null, "direct-v1",
                "{\"direct-max-chars\":80000,\"parallelism\":16}");
        assertThat(extractionRunRepository.findById(runId).orElseThrow().getStatus())
                .isEqualTo(ExtractionRun.Status.RUNNING);

        extractionRunAuditService.finish(runId, ExtractionRun.Status.SUCCEEDED, null, 4, 1, "direct-v1");

        ExtractionRun run = extractionRunRepository.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(ExtractionRun.Status.SUCCEEDED);
        assertThat(run.getGoalsCreated()).isEqualTo(4);
        assertThat(run.getFailedSessions()).isEqualTo(1);
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void directSourcesPreferLinesThenUseFiguresAndRejectInvalidFigureIndices() throws Exception {
        Course course = new Course("Figure source precedence");
        // Figure evidence is opt-in per course, and this test is about what it does once opted in.
        course.setFiguresEnabled(true);
        courseRepository.save(course);
        String rawText = "verbatim line";
        Document document = documentRepository.save(
                new Document(course, "figures.pdf", "application/pdf", rawText));
        document.setPageOffsets(new int[]{0, rawText.length(), rawText.length()});
        documentRepository.saveAndFlush(document);
        documentSectionRepository.saveAndFlush(new DocumentSection(
                document, 0, "Section", 0, rawText.length(), 1, 2));
        pageDescriptionRepository.saveAndFlush(
                new PageDescription(document, 2, "A diagram teaches the process.", "vision-test"));

        when(sessionExtractionService.extract(eq("Section"), eq(rawText), eq("en"), eq("English"), eq(null), anyList()))
                .thenReturn(List.of(
                        new ExtractedSkill("Text outcome", "Text", GoalKind.IMPLICIT,
                                0, 0, 0, List.of()),
                        figureSkill("Figure outcome", 0),
                        figureSkill("Unsupported outcome", 4)));
        stubEmbedAll(Map.of(
                "Text outcome", orthogonalEmbedding(0),
                "Figure outcome", orthogonalEmbedding(1),
                "Unsupported outcome", orthogonalEmbedding(2)));

        startExtraction(course.getId());

        Map<Long, String> goalTextById = goalRepository.findByCourseId(course.getId()).stream()
                .collect(Collectors.toMap(LearningGoal::getId, LearningGoal::getText));
        Map<String, GoalSource> sources = goalSourceRepository.findAll().stream()
                .filter(source -> source.getDocument().getId().equals(document.getId()))
                .collect(Collectors.toMap(source -> goalTextById.get(source.getGoal().getId()), source -> source));
        assertThat(sources.get("Text outcome").getEvidenceKind()).isEqualTo(EvidenceKind.TEXT);
        assertThat(sources.get("Figure outcome").getEvidenceKind()).isEqualTo(EvidenceKind.FIGURE);
        assertThat(sources.get("Figure outcome").getPage()).isEqualTo(2);
        assertThat(sources.get("Figure outcome").getSnippet()).isEmpty();
        assertThat(sources.get("Unsupported outcome").getEvidenceKind()).isEqualTo(EvidenceKind.UNSUPPORTED);
    }

    /**
     * A section too large for one extraction call becomes several units on the ONE session node its
     * section created, and each unit is extracted on its own. The grounding assertion is the point:
     * every window reports its source lines relative to its own text, so a goal from the third window
     * must resolve to the third window's line — which only holds while each goal carries its own unit
     * through classification. Collapsing the units (one per node) would silently resolve all three
     * against the first window and give three identical snippets.
     */
    @Test
    void oversizedSectionIsSplitIntoWindowsOnOneSession() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        // direct-max-chars is 80 here, so a 180-character document cuts into three 60-character
        // line-aligned windows.
        String first = paddedLine("Apply the first capability.");
        String second = paddedLine("Apply the second capability.");
        String third = paddedLine("Apply the third capability.");
        Document document = documentRepository.save(
                new Document(course, "combined.pdf", "application/pdf", first + second + third));

        when(sessionExtractionService.extract(eq("combined.pdf"), eq(first), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the first capability.", "First",
                        GoalKind.EXPLICIT, ""), 0, 0)));
        when(sessionExtractionService.extract(eq("combined.pdf"), eq(second), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the second capability.", "Second",
                        GoalKind.EXPLICIT, ""), 0, 0)));
        when(sessionExtractionService.extract(eq("combined.pdf"), eq(third), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the third capability.", "Third",
                        GoalKind.EXPLICIT, ""), 0, 0)));
        stubEmbedAll(Map.of(
                "Apply the first capability.", orthogonalEmbedding(0),
                "Apply the second capability.", orthogonalEmbedding(1),
                "Apply the third capability.", orthogonalEmbedding(2)));

        startExtraction(course.getId());

        List<LearningGoal> extracted = goalRepository.findByCourseId(course.getId()).stream()
                .filter(goal -> goal.getOrigin() == GoalOrigin.EXTRACTED)
                .toList();
        assertThat(extracted).extracting(LearningGoal::getShortLabel)
                .containsExactlyInAnyOrder("First", "Second", "Third");

        // The split is invisible above the extraction unit: one session node owns all three windows.
        assertThat(extracted).extracting(goal -> goal.getHierarchyNode().getId())
                .containsOnly(extracted.getFirst().getHierarchyNode().getId());
        assertThat(hierarchyRepository.findByCourseId(course.getId()))
                .filteredOn(node -> node.getLevel() == HierarchyLevel.SESSION)
                .singleElement()
                .satisfies(node -> assertThat(node.getLabel()).isEqualTo("combined.pdf"));

        Map<Long, String> goalTextById = extracted.stream()
                .collect(Collectors.toMap(LearningGoal::getId, LearningGoal::getText));
        Map<String, String> snippets = goalSourceRepository.findAll().stream()
                .filter(source -> source.getDocument().getId().equals(document.getId()))
                .collect(Collectors.toMap(source -> goalTextById.get(source.getGoal().getId()),
                        source -> source.getSnippet().strip()));
        assertThat(snippets).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Apply the first capability.", "Apply the first capability.",
                "Apply the second capability.", "Apply the second capability.",
                "Apply the third capability.", "Apply the third capability."));

        assertThat(extractionRunRepository.findByCourseId(course.getId()))
                .singleElement()
                .satisfies(run -> assertThat(run.getPromptVersion())
                        .isEqualTo(SessionExtractionService.PROMPT_VERSION));
    }

    /** Pads a sentence to exactly 59 characters plus a newline, so windows land on known offsets. */
    private static String paddedLine(String sentence) {
        return sentence + " ".repeat(59 - sentence.length()) + "\n";
    }

    /**
     * The same split on a PDF, where page offsets exist: the cut follows page boundaries rather than
     * lines, so no slide is torn in half and every window keeps a true page range. Each goal must
     * resolve to the page of its own window.
     */
    @Test
    void oversizedSectionSplitsOnPageBoundaries() throws Exception {
        Course course = courseRepository.save(new Course("Paged sections"));
        // Four 50-character pages against an 80-character budget: whole pages pack one per window.
        List<String> pages = List.of(
                pagedLine("Apply the page one capability."),
                pagedLine("Apply the page two capability."),
                pagedLine("Apply the page three capability."),
                pagedLine("Apply the page four capability."));
        String rawText = String.join("", pages);
        Document document = documentRepository.save(
                new Document(course, "paged.pdf", "application/pdf", rawText));
        document.setPageOffsets(new int[]{0, 50, 100, 150, 200});
        documentRepository.saveAndFlush(document);
        documentSectionRepository.saveAndFlush(new DocumentSection(
                document, 0, "Combined chapter", 0, rawText.length(), 1, 4));

        List<String> labels = List.of("Page one", "Page two", "Page three", "Page four");
        for (int i = 0; i < pages.size(); i++) {
            when(sessionExtractionService.extract(eq("Combined chapter"), eq(pages.get(i)),
                    eq("en"), eq("English"), eq(null)))
                    .thenReturn(List.of(skill(new ExtractedGoal(labels.get(i) + " outcome", labels.get(i),
                            GoalKind.EXPLICIT, ""), 0, 0)));
        }
        stubEmbedAll(Map.of(
                "Page one outcome", orthogonalEmbedding(0),
                "Page two outcome", orthogonalEmbedding(1),
                "Page three outcome", orthogonalEmbedding(2),
                "Page four outcome", orthogonalEmbedding(3)));

        startExtraction(course.getId());

        List<LearningGoal> extracted = goalRepository.findByCourseId(course.getId()).stream()
                .filter(goal -> goal.getOrigin() == GoalOrigin.EXTRACTED)
                .toList();
        assertThat(extracted).extracting(LearningGoal::getShortLabel)
                .containsExactlyInAnyOrderElementsOf(labels);
        assertThat(extracted).extracting(goal -> goal.getHierarchyNode().getId())
                .containsOnly(extracted.getFirst().getHierarchyNode().getId());
        assertThat(hierarchyRepository.findByCourseId(course.getId()))
                .filteredOn(node -> node.getLevel() == HierarchyLevel.SESSION)
                .singleElement()
                .satisfies(node -> assertThat(node.getLabel()).isEqualTo("Combined chapter"));

        Map<Long, String> goalTextById = extracted.stream()
                .collect(Collectors.toMap(LearningGoal::getId, LearningGoal::getText));
        Map<String, Integer> pageByGoal = goalSourceRepository.findAll().stream()
                .filter(source -> source.getDocument().getId().equals(document.getId()))
                .collect(Collectors.toMap(source -> goalTextById.get(source.getGoal().getId()),
                        GoalSource::getPage));
        assertThat(pageByGoal).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Page one outcome", 1,
                "Page two outcome", 2,
                "Page three outcome", 3,
                "Page four outcome", 4));
    }

    /** Pads a sentence to exactly 49 characters plus a newline, giving 50-character pages. */
    private static String pagedLine(String sentence) {
        return sentence + " ".repeat(49 - sentence.length()) + "\n";
    }

    @Test
    void extractionPersistsShortLabelOnTerminalCompetencies() throws Exception {
        Course course = courseRepository.save(new Course("Terminal competency labels"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        stubCompactPlan("Terminal Capability", List.of(List.of(0)));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        startExtraction(course.getId());

        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(LearningGoal::getShortLabel)
                .isEqualTo("Terminal Capability");
    }

    @Test
    void overfullCompetencyIsConsolidatedWithoutDroppingSourceBackedGoals() throws Exception {
        Course course = courseRepository.save(new Course("Consolidated competency"));
        String material = "Apply one. Apply two. Apply three. Apply four. Apply five. Apply six.";
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", material));
        List<ExtractedSkill> extracted = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> skill(new ExtractedGoal(
                        "Apply capability " + index + ".", "Capability " + index,
                        GoalKind.EXPLICIT, "...capability..."), index - 1, index - 1))
                .toList();
        when(sessionExtractionService.extract(eq("session.pdf"), eq(material), eq("en"), eq("English"), eq(null)))
                .thenReturn(extracted);
        when(taxonomyService.classifyBatch(anyList(), eq(null))).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream()
                    .map(text -> new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL))
                    .toList();
        });
        stubCompactPlan("Course Methods", List.of(List.of(0, 1, 2), List.of(3, 4, 5)));
        stubEmbedAll(java.util.stream.IntStream.rangeClosed(1, 6).boxed()
                .collect(Collectors.toMap(index -> "Apply capability " + index + ".",
                        index -> orthogonalEmbedding(index - 1))));

        startExtraction(course.getId());

        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId());
        LearningGoal terminal = goals.stream()
                .filter(goal -> goal.getOrigin() == GoalOrigin.TERMINAL)
                .findFirst().orElseThrow();
        // The two sub-skills are ELECTED extracted outcomes, not generated nodes: the tree adds no
        // goal of its own below the terminal, and each elected node keeps its own source.
        assertThat(goals).noneMatch(goal -> goal.getOrigin() == GoalOrigin.SYNTHESIZED);
        // Resolve through the repository: the edge's source is a lazy proxy and this runs outside
        // a session, so navigating it directly would throw.
        List<LearningGoal> elected = goalRelationshipRepository.findByTargetId(terminal.getId()).stream()
                .map(relationship -> goalRepository.findById(relationship.getSource().getId()).orElseThrow())
                .toList();
        assertThat(elected).hasSize(2)
                .allSatisfy(node -> assertThat(node.getOrigin()).isEqualTo(GoalOrigin.EXTRACTED));
        // Each elected node carries its two group-mates as SUPPORTS; it never supports itself.
        assertThat(elected).allSatisfy(node ->
                assertThat(goalRelationshipRepository.findByTargetId(node.getId()))
                        .hasSize(2)
                        .allSatisfy(relationship -> {
                            assertThat(relationship.getType()).isEqualTo(RelationshipType.SUPPORTS);
                            assertThat(relationship.getSource().getId()).isNotEqualTo(node.getId());
                        }));
        assertThat(goals).filteredOn(goal -> goal.getOrigin() == GoalOrigin.EXTRACTED).hasSize(6);
    }

    /**
     * A goal the assignment step could not place must still reach the tree. The client only renders
     * goals reachable from a terminal, so leaving it unlinked would make it invisible — the catch-all
     * keeps it visible without asserting it belongs to a competency it does not serve.
     */
    @Test
    void compactPlanCoversEverySourceWithoutCatchAllTerminal() throws Exception {
        Course course = courseRepository.save(new Course("Unmatched goals"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        // The model placed the only goal nowhere.
        stubCompactPlan("Covered Capability", List.of(List.of(0)));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        startExtraction(course.getId());

        List<LearningGoal> terminals = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .toList();
        assertThat(terminals).singleElement()
                .extracting(LearningGoal::getShortLabel)
                .isEqualTo("Covered Capability");
        LearningGoal extracted = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> g.getOrigin() == GoalOrigin.EXTRACTED)
                .findFirst()
                .orElseThrow();
        // A lone outcome is elected to represent its own group, so it becomes the visible sub-skill
        // and contributes to the terminal directly. Only its group-mates would carry SUPPORTS.
        assertThat(goalRelationshipRepository.findBySourceId(extracted.getId()))
                .singleElement()
                .satisfies(relationship -> {
                    assertThat(relationship.getType()).isEqualTo(RelationshipType.CONTRIBUTES_TO);
                    assertThat(goalRepository.findById(relationship.getTarget().getId()).orElseThrow()
                            .getOrigin()).isEqualTo(GoalOrigin.TERMINAL);
                });
    }

    @Test
    void unmatchedGoalsReceiveSemanticCompetencyRepairBeforeCatchAll() throws Exception {
        Course course = courseRepository.save(new Course("Semantic coverage repair"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));
        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."),
                eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal(
                        "Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        stubCompactPlan("Repaired Capability", List.of(List.of(0)));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        startExtraction(course.getId());

        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(goal -> goal.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(LearningGoal::getShortLabel)
                .isEqualTo("Repaired Capability");
    }

    /**
     * The naming call may propose a competency the assignment then gives nothing. The course does not
     * build toward it, so it must not reach the tree as a childless top-level node.
     */
    @Test
    void competenciesThatReceiveNoGoalsAreNotPersisted() throws Exception {
        Course course = courseRepository.save(new Course("Empty competency"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        // Everything lands on the first competency; the second is left empty.
        stubCompactPlan("Claimed", List.of(List.of(0)));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        startExtraction(course.getId());

        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(LearningGoal::getShortLabel)
                .isEqualTo("Claimed");
    }

    /**
     * The rebuild exists so the tree can be iterated on without re-reading documents: it replaces the
     * terminals and their edges while the extracted goals stay exactly where they were.
     */
    @Test
    void rebuildReplacesTheTreeAndKeepsExtractedGoals() throws Exception {
        Course course = courseRepository.save(new Course("Rebuild"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        stubCompactPlan("First Label", List.of(List.of(0)));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        startExtraction(course.getId());
        List<Long> extractedIdsBefore = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> g.getOrigin() == GoalOrigin.EXTRACTED)
                .map(LearningGoal::getId)
                .toList();
        LearningGoal legacySkill = goalRepository.findById(extractedIdsBefore.getFirst()).orElseThrow();
        legacySkill.setRole(null);
        goalRepository.saveAndFlush(legacySkill);
        LearningGoal legacyKnowledge = new LearningGoal(course, "Explain the legacy basics.", GoalKind.EXPLICIT);
        legacyKnowledge.setRole(null);
        legacyKnowledge.setBloomLevel(BloomLevel.UNDERSTAND);
        legacyKnowledge.setHierarchyNode(legacySkill.getHierarchyNode());
        legacyKnowledge = goalRepository.saveAndFlush(legacyKnowledge);
        goalRelationshipRepository.save(new GoalRelationship(legacyKnowledge, legacySkill,
                RelationshipType.CONTRIBUTES_TO, 1.0, RelationshipOrigin.HIERARCHY));
        extractedIdsBefore = java.util.stream.Stream.concat(extractedIdsBefore.stream(),
                java.util.stream.Stream.of(legacyKnowledge.getId())).toList();

        stubCompactPlan("Second Label", List.of(List.of(0)));

        mockMvc.perform(post("/api/courses/{id}/competency-tree", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competencies").value(1))
                .andExpect(jsonPath("$.unmatchedGoals").value(0));

        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(LearningGoal::getShortLabel)
                .isEqualTo("Second Label");
        assertThat(goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> g.getOrigin() == GoalOrigin.EXTRACTED
                        || "Explain the legacy basics.".equals(g.getText()))
                .map(LearningGoal::getId))
                .containsExactlyInAnyOrderElementsOf(extractedIdsBefore);
        assertThat(hierarchyRepository.findByCourseId(course.getId()))
                .filteredOn(n -> n.getLevel() == HierarchyLevel.COMPETENCY)
                .hasSize(1);
        assertThat(goalRepository.findById(legacySkill.getId()).orElseThrow().getRole()).isNull();
        assertThat(goalRelationshipRepository.findBySourceId(legacyKnowledge.getId())).isEmpty();
    }

    /**
     * Rebuilding twice must produce the same tree, not a thicker one. With elected sub-skills both
     * ends of a tree edge are surviving extracted goals, so a rebuild that failed to delete its own
     * edges would stack a second copy on every run and silently inflate each group.
     */
    @Test
    void repeatedRebuildsDoNotAccumulateTreeEdges() throws Exception {
        Course course = courseRepository.save(new Course("Repeated rebuild"));
        String material = "Apply one. Apply two. Apply three. Apply four. Apply five. Apply six.";
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", material));
        List<ExtractedSkill> extracted = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> skill(new ExtractedGoal(
                        "Apply capability " + index + ".", "Capability " + index,
                        GoalKind.EXPLICIT, "...capability..."), index - 1, index - 1))
                .toList();
        when(sessionExtractionService.extract(eq("session.pdf"), eq(material), eq("en"), eq("English"), eq(null)))
                .thenReturn(extracted);
        when(taxonomyService.classifyBatch(anyList(), eq(null))).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream()
                    .map(text -> new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL))
                    .toList();
        });
        stubCompactPlan("Course Methods", List.of(List.of(0, 1, 2), List.of(3, 4, 5)));
        startExtraction(course.getId());

        List<Long> courseGoalIds = goalRepository.findByCourseId(course.getId()).stream()
                .map(LearningGoal::getId).toList();
        int afterExtraction = goalRelationshipRepository.findBySourceIdIn(courseGoalIds).size();

        mockMvc.perform(post("/api/courses/{id}/competency-tree?force=true", course.getId()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/courses/{id}/competency-tree?force=true", course.getId()))
                .andExpect(status().isOk());

        List<Long> rebuiltGoalIds = goalRepository.findByCourseId(course.getId()).stream()
                .map(LearningGoal::getId).toList();
        assertThat(goalRelationshipRepository.findBySourceIdIn(rebuiltGoalIds))
                .as("two further rebuilds must not add edges")
                .hasSize(afterExtraction);
        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(goal -> goal.getOrigin() == GoalOrigin.EXTRACTED)
                .hasSize(6);
    }

    /** Extraction creates knowledge → skill edges, and rebuilding preserves them while replacing tree edges. */
    @Test
    void rebuildLeavesNoEdgesFromThePreviousTree() throws Exception {
        Course course = courseRepository.save(new Course("Rebuild edges"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf",
                "Apply the capability.\n\nUnderstand the basics."));

        when(sessionExtractionService.extract(eq("session.pdf"), anyString(), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(
                        new ExtractedSkill("Apply the capability.", "Capability", GoalKind.EXPLICIT,
                                0, 0, List.of(new ExtractedSkill.Knowledge(
                                        "Understand the basics.", "Basics", GoalKind.EXPLICIT, 1, 1)))));
        when(taxonomyService.classifyBatch(anyList(), eq(null))).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream()
                    .map(t -> t.startsWith("Understand")
                            ? new TaxonomyClassification(BloomLevel.UNDERSTAND, SoloLevel.UNISTRUCTURAL)
                            : new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL))
                    .toList();
        });
        stubCompactPlan("Capability", List.of(List.of(0)));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0),
                "Understand the basics.", orthogonalEmbedding(1)));

        startExtraction(course.getId());

        LearningGoal knowledgeBefore = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> "Understand the basics.".equals(g.getText()))
                .findFirst()
                .orElseThrow();
        LearningGoal skillBefore = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> "Apply the capability.".equals(g.getText()))
                .findFirst()
                .orElseThrow();
        assertThat(knowledgeBefore.getRole()).isEqualTo(GoalRole.KNOWLEDGE);
        assertThat(skillBefore.getRole()).isEqualTo(GoalRole.SKILL);
        assertThat(goalSourceRepository.findByGoalId(skillBefore.getId()))
                .singleElement()
                .extracting(GoalSource::getSnippet)
                .isEqualTo("Apply the capability.");
        assertThat(goalSourceRepository.findByGoalId(knowledgeBefore.getId()))
                .singleElement()
                .extracting(GoalSource::getSnippet)
                .isEqualTo("Understand the basics.");
        assertThat(goalRelationshipRepository.findBySourceId(knowledgeBefore.getId()))
                .extracting(r -> r.getTarget().getId())
                .contains(skillBefore.getId());

        stubCompactPlan("Rebuilt Capability", List.of(List.of(0)));
        mockMvc.perform(post("/api/courses/{id}/competency-tree", course.getId()))
                .andExpect(status().isOk());

        LearningGoal knowledge = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> "Understand the basics.".equals(g.getText()))
                .findFirst()
                .orElseThrow();
        LearningGoal skill = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> "Apply the capability.".equals(g.getText()))
                .findFirst()
                .orElseThrow();
        // Compare ids, not text: the edge's target is a lazy proxy and this runs outside a session.
        assertThat(goalRelationshipRepository.findBySourceId(knowledge.getId()))
                .as("the extraction edge must survive the rebuild")
                .extracting(r -> r.getTarget().getId())
                .contains(skill.getId());
        // The lone extracted skill is elected as its own group's sub-skill, so after the rebuild it
        // hangs off the fresh terminal directly rather than supporting a generated node.
        assertThat(goalRelationshipRepository.findBySourceId(skill.getId()))
                .singleElement()
                .satisfies(relationship -> assertThat(relationship.getType())
                        .isEqualTo(RelationshipType.CONTRIBUTES_TO));
    }

    /**
     * A rebuild synthesises the replacement before it destroys anything, so a model outage mid-rebuild
     * cannot leave the course with its tree deleted and nothing in its place.
     */
    @Test
    void failedRebuildLeavesTheExistingTreeIntact() throws Exception {
        Course course = courseRepository.save(new Course("Rebuild failure"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        stubCompactPlan("Survivor", List.of(List.of(0)));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        startExtraction(course.getId());

        // The model goes down before the rebuild can synthesise a replacement.
        when(compactTaxonomySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenThrow(new IllegalStateException("model unavailable"));

        mockMvc.perform(post("/api/courses/{id}/competency-tree", course.getId()))
                .andExpect(status().isBadGateway());

        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(LearningGoal::getShortLabel)
                .isEqualTo("Survivor");
    }

    /**
     * A rebuild replaces exactly the nodes an instructor may have approved or typed, and nothing
     * records what they were — so it refuses rather than destroying them silently.
     */
    @Test
    void rebuildRefusesWhenTheTreeHoldsApprovedWork() throws Exception {
        Course course = courseRepository.save(new Course("Rebuild guard"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        startExtraction(course.getId());

        LearningGoal terminal = goalRepository.findByCourseId(course.getId()).stream()
                .filter(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .findFirst()
                .orElseThrow();
        terminal.setStatus(de.tum.cit.hestia.learninggoalhub.goal.GoalStatus.APPROVED);
        goalRepository.save(terminal);

        mockMvc.perform(post("/api/courses/{id}/competency-tree", course.getId()))
                .andExpect(status().isConflict());
        assertThat(goalRepository.findByCourseId(course.getId()))
                .anyMatch(g -> g.getOrigin() == GoalOrigin.TERMINAL);

        // The override is what makes the destruction deliberate.
        mockMvc.perform(post("/api/courses/{id}/competency-tree?force=true", course.getId()))
                .andExpect(status().isOk());
        assertThat(goalRepository.findByCourseId(course.getId()))
                .filteredOn(g -> g.getOrigin() == GoalOrigin.TERMINAL)
                .singleElement()
                .satisfies(g -> assertThat(g.getStatus())
                        .isEqualTo(de.tum.cit.hestia.learninggoalhub.goal.GoalStatus.PENDING));
    }

    /**
     * Without a single placement the tree would be one bucket holding the whole course, which is
     * worse than no tree. Nothing may be persisted in that case — not even the competency root.
     */
    @Test
    void failedTerminalSynthesisKeepsExtractedGoalsForTreeOnlyRetry() throws Exception {
        Course course = courseRepository.save(new Course("Terminal synthesis failure"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."),
                eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal(
                        "Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        when(compactTaxonomySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenThrow(new IllegalStateException("HTTP 500 - No response body available"));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        startExtractionExpectingFailure(course.getId());

        String status = awaitExtraction(course.getId());
        assertThat(status)
                .contains("\"status\":\"FAILED\"")
                .contains("The learning goals were saved")
                .contains("Compact competency taxonomy synthesis failed")
                .contains("HTTP 500");
        assertThat(goalRepository.findByCourseId(course.getId()))
                .singleElement()
                .satisfies(goal -> assertThat(goal.getOrigin()).isEqualTo(GoalOrigin.EXTRACTED));
        assertThat(hierarchyRepository.existsByCourseIdAndLevel(course.getId(), HierarchyLevel.MODULE)).isTrue();
        assertThat(hierarchyRepository.existsByCourseIdAndLevel(course.getId(), HierarchyLevel.COMPETENCY)).isFalse();

        stubCompactPlan("Apply Course Capability", List.of(List.of(0)));
        mockMvc.perform(post("/api/courses/{id}/competency-tree", course.getId()))
                .andExpect(status().isOk());

        assertThat(awaitExtraction(course.getId())).contains("\"status\":\"SUCCEEDED\"");
        assertThat(hierarchyRepository.existsByCourseIdAndLevel(course.getId(), HierarchyLevel.COMPETENCY)).isTrue();
        assertThat(goalRepository.findByCourseIdAndOriginIn(course.getId(), List.of(GoalOrigin.EXTRACTED)))
                .hasSize(1);
    }

    @Test
    void failedAssignmentKeepsExtractedGoalsForTreeOnlyRetry() throws Exception {
        Course course = courseRepository.save(new Course("Assignment failure"));
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        when(compactTaxonomySynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenThrow(new IllegalStateException("assignment call failed"));
        stubEmbedAll(Map.of("Apply the capability.", orthogonalEmbedding(0)));

        startExtractionExpectingFailure(course.getId());

        String status = awaitExtraction(course.getId());
        assertThat(status)
                .contains("\"status\":\"FAILED\"")
                .contains("Compact competency taxonomy synthesis failed")
                .contains("assignment call failed");
        assertThat(goalRepository.findByCourseId(course.getId()))
                .singleElement()
                .satisfies(goal -> assertThat(goal.getOrigin()).isEqualTo(GoalOrigin.EXTRACTED));
        assertThat(hierarchyRepository.existsByCourseIdAndLevel(course.getId(), HierarchyLevel.MODULE)).isTrue();
        assertThat(hierarchyRepository.existsByCourseIdAndLevel(course.getId(), HierarchyLevel.COMPETENCY)).isFalse();
        assertThat(extractionRunRepository.findByCourseId(course.getId()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.getStatus()).isEqualTo(ExtractionRun.Status.FAILED);
                    assertThat(run.getError()).contains("Compact competency taxonomy synthesis failed");
                    assertThat(run.getGoalsCreated()).isEqualTo(1);
                });
    }

    @Test
    void failedSessionFailsTheRunWithoutPublishingPartialGoals() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        String failing = "short session text";
        String healthy = "healthy session text";
        documentRepository.save(new Document(course, "failed.pdf", "application/pdf", failing));
        documentRepository.save(new Document(course, "healthy.pdf", "application/pdf", healthy));
        when(sessionExtractionService.extract(eq("failed.pdf"), eq(failing), eq("en"), eq("English"), eq(null)))
                .thenThrow(new RuntimeException("direct extraction failed"));
        when(sessionExtractionService.extract(eq("healthy.pdf"), eq(healthy), eq("en"), eq("English"), eq(null)))
                .thenReturn(List.of(skill(new ExtractedGoal(
                        "Apply test-driven development.", GoalKind.EXPLICIT, "...healthy snippet..."))));

        startExtractionExpectingFailure(course.getId());

        String status = awaitExtraction(course.getId());
        assertThat(status)
                .contains("\"status\":\"FAILED\"")
                .contains("\"failedSessions\":1")
                .contains("\"failedSessionNames\":[\"failed.pdf\"]");
        mockMvc.perform(get("/api/courses/{id}", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extractionStatus").value("FAILED"));
        assertThat(goalRepository.findByCourseId(course.getId())).isEmpty();
        assertThat(extractionRunRepository.findByCourseId(course.getId()))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.getStatus()).isEqualTo(ExtractionRun.Status.FAILED);
                    assertThat(run.getError()).contains("failed.pdf");
                    assertThat(run.getFinishedAt()).isNotNull();
                    assertThat(run.getGoalsCreated()).isNull();
                    assertThat(run.getFailedSessions()).isEqualTo(1);
                });
    }

    @Test
    void identicalGoalsAcrossDocumentsRemainSeparateGoals() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture body"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise body"));

        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture body"), eq("en"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Apply test-driven development.", GoalKind.EXPLICIT, "...lecture snippet..."))
        ));
        when(sessionExtractionService.extract(eq("exercise.pdf"), eq("exercise body"), eq("en"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Apply TDD when writing code.", GoalKind.EXPLICIT, "...exercise snippet..."))
        ));
        // Both goals get the exact same embedding; extraction no longer performs embedding deduplication.
        stubEmbedAll(Map.of());

        startExtraction(course.getId());

        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId()).stream()
                .filter(goal -> goal.getOrigin() == GoalOrigin.EXTRACTED)
                .toList();
        assertThat(goals).hasSize(2);
        assertThat(goals)
                .flatExtracting(goal -> goalSourceRepository.findByGoalId(goal.getId()))
                .extracting(s -> s.getDocument().getId())
                .containsExactlyInAnyOrder(lecture.getId(), exercise.getId());
    }

    @Test
    void duplicateExtractionsFromSameDocumentCreateSeparateGoals() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture body"));

        // A direct response can contain closely related goals; each enriched goal is persisted.
        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture body"), eq("en"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Apply TDD.", GoalKind.EXPLICIT, "...first snippet...")),
                skill(new ExtractedGoal("Apply TDD (rephrased).", GoalKind.EXPLICIT, "...second snippet..."))
        ));
        stubEmbedAll(Map.of());

        startExtraction(course.getId());

        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId()).stream()
                .filter(goal -> goal.getOrigin() == GoalOrigin.EXTRACTED)
                .toList();
        assertThat(goals).hasSize(2);
        assertThat(goals)
                .flatExtracting(goal -> goalSourceRepository.findByGoalId(goal.getId()))
                .hasSize(2)
                .allSatisfy(s -> assertThat(s.getDocument().getId()).isEqualTo(lecture.getId()));
    }

    @Test
    void unknownCourseReturns404() throws Exception {
        mockMvc.perform(post("/api/courses/{id}/extract", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusReturnsNoContentBeforeAnyRun() throws Exception {
        Course course = courseRepository.save(new Course("Untouched"));

        mockMvc.perform(get("/api/courses/{id}/extract/status", course.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    void statusReportsSucceededWithSummaryAfterExtraction() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture text about TDD"));

        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture text about TDD"), eq("en"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Apply test-driven development.", GoalKind.EXPLICIT, "...failing test first..."))
        ));
        stubEmbedAll(Map.of());

        startExtraction(course.getId());

        mockMvc.perform(get("/api/courses/{id}/extract/status", course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.phase").value("SYNTHESIZING"))
                .andExpect(jsonPath("$.percent").value(100))
                .andExpect(jsonPath("$.summary.goalsCreated").value(1))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void extractionSplitsOneDocumentIntoItsStructuralSectionsAndRoutesChunksByOffset() throws Exception {
        Course course = courseRepository.save(new Course("Software Engineering"));
        // One uploaded PDF whose bookmarks split it into a lecture chapter and an exercise chapter.
        // The runner chunks each section's character range and attaches its goals to that section.
        String sessionText = "Lecture 3 Testing. Students apply TDD by writing a failing test first.\n";
        String exerciseText = "Exercise 3.2 Kata. Practise the refactoring kata until it is fluent.\n";
        Document combined = documentRepository.save(new Document(course, "chapters.pdf", "application/pdf",
                sessionText + exerciseText));
        documentSectionRepository.save(
                new DocumentSection(combined, 0, "Lecture 3: Testing", 0, sessionText.length()));
        documentSectionRepository.save(new DocumentSection(combined, 1, "Exercise 3.2: Kata",
                sessionText.length(), sessionText.length() + exerciseText.length()));

        when(sessionExtractionService.extract(eq("Lecture 3: Testing"), eq(sessionText), eq("en"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Apply TDD.", GoalKind.EXPLICIT, "...failing test first...")),
                skill(new ExtractedGoal("Understand SE scope.", GoalKind.IMPLICIT, "...overview..."))
        ));
        when(sessionExtractionService.extract(eq("Exercise 3.2: Kata"), eq(exerciseText), eq("en"), eq("English"), eq(null))).thenReturn(List.of(
                skill(new ExtractedGoal("Practise TDD kata.", GoalKind.EXPLICIT, "...kata..."))
        ));
        stubEmbedAll(Map.of(
                "Apply TDD.", orthogonalEmbedding(0),
                "Understand SE scope.", orthogonalEmbedding(1),
                "Practise TDD kata.", orthogonalEmbedding(2)));

        startExtraction(course.getId());

        // Module root + one session node + one exercise node (the title keyword picks EXERCISE).
        List<HierarchyNode> nodes = hierarchyRepository.findByCourseId(course.getId());
        assertThat(nodes).extracting(HierarchyNode::getLevel)
                .contains(HierarchyLevel.MODULE, HierarchyLevel.SESSION, HierarchyLevel.EXERCISE,
                        HierarchyLevel.COMPETENCY);

        // Each goal is attached to the node of the section its chunk came from (deterministic, by
        // offset). Labels are resolved through the already-loaded nodes: the goal's hierarchyNode is a
        // lazy proxy and the session is closed, but reading its id never triggers initialization.
        Map<Long, String> labelsByNodeId = nodes.stream()
                .collect(Collectors.toMap(HierarchyNode::getId, HierarchyNode::getLabel));
        List<LearningGoal> goals = goalRepository.findByCourseId(course.getId());
        assertThat(goals).filteredOn(g -> g.getOrigin() != GoalOrigin.SYNTHESIZED)
                .allSatisfy(g -> assertThat(g.getHierarchyNode()).isNotNull());
        assertThat(goals)
                .filteredOn(g -> g.getText().equals("Practise TDD kata."))
                .singleElement()
                .satisfies(g -> assertThat(labelsByNodeId.get(g.getHierarchyNode().getId()))
                        .isEqualTo("Exercise 3.2: Kata"));
        assertThat(goals)
                .filteredOn(g -> g.getText().equals("Apply TDD."))
                .singleElement()
                .satisfies(g -> assertThat(labelsByNodeId.get(g.getHierarchyNode().getId()))
                        .isEqualTo("Lecture 3: Testing"));
        LearningGoal lectureGoal = goals.stream()
                .filter(g -> g.getText().equals("Apply TDD."))
                .findFirst().orElseThrow();
        LearningGoal exerciseGoal = goals.stream()
                .filter(g -> g.getText().equals("Practise TDD kata."))
                .findFirst().orElseThrow();
        assertThat(lectureGoal.getLectureOrder()).isLessThan(exerciseGoal.getLectureOrder());
    }

    private static float[] orthogonalEmbedding(int slot) {
        float[] v = new float[4096];
        v[slot] = 1.0f;
        return v;
    }

    /**
     * Stubs the batched goal embedding: each text maps to its vector in {@code byText}, defaulting to
     * {@code orthogonalEmbedding(0)} for any text not listed. The returned list is aligned to the
     * batch's input order, mirroring the real {@code embedAll}.
     */
    private void stubEmbedAll(Map<String, float[]> byText) {
        when(embeddingService.embedAll(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> byText.getOrDefault(t, orthogonalEmbedding(0))).toList();
        });
    }
}
