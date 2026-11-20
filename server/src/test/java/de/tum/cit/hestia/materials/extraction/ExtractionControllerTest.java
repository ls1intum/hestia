package de.tum.cit.hestia.materials.extraction;

import de.tum.cit.hestia.materials.TestTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import de.tum.cit.hestia.materials.topictree.TopicTreeSynthesizer;
import de.tum.cit.hestia.materials.GoalFixtures;
import de.tum.cit.hestia.materials.goal.PlacedGoal;
import de.tum.cit.hestia.core.LearningGoalApi;
import java.util.UUID;
import de.tum.cit.hestia.materials.course.ExtractionStatus;
import de.tum.cit.hestia.materials.document.Document;
import de.tum.cit.hestia.materials.document.DocumentRepository;
import de.tum.cit.hestia.materials.document.DocumentSection;
import de.tum.cit.hestia.materials.document.DocumentSectionRepository;
import de.tum.cit.hestia.materials.document.PageDescription;
import de.tum.cit.hestia.materials.document.PageDescriptionRepository;
import de.tum.cit.hestia.materials.document.PageDescriptionService;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import de.tum.cit.hestia.materials.goal.EvidenceKind;
import de.tum.cit.hestia.core.GoalKind;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.materials.goal.GoalSource;
import de.tum.cit.hestia.materials.goal.GoalSourceRepository;
import de.tum.cit.hestia.materials.taxonomy.SoloLevel;
import de.tum.cit.hestia.materials.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNode;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNodeRepository;
import de.tum.cit.hestia.materials.goal.RelationshipOrigin;
import de.tum.cit.hestia.materials.goal.RelationshipType;
import de.tum.cit.hestia.materials.goal.SourceMatchQuality;
import de.tum.cit.hestia.materials.taxonomy.TaxonomyClassification;
import de.tum.cit.hestia.materials.taxonomy.TaxonomyService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(MaterialsTestConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"hestia.extraction.unit-max-chars=80",
        "hestia.extraction.skill-target-chars=0"})
class ExtractionControllerTest extends MaterialsTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LearningGoalApi goalApi;

    @Autowired
    private de.tum.cit.hestia.materials.course.MaterialSettingsService settingsService;

    @Autowired
    private de.tum.cit.hestia.materials.course.MaterialSettingsRepository settingsRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentSectionRepository documentSectionRepository;

    @Autowired
    private PageDescriptionRepository pageDescriptionRepository;

    @Autowired
    private GoalSourceRepository goalSourceRepository;

    @Autowired
    private HierarchyNodeRepository hierarchyRepository;

    @Autowired
    private ExtractionRunRepository extractionRunRepository;

    @Autowired
    private ExtractionRunAuditService extractionRunAuditService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SessionExtractionService sessionExtractionService;

    @MockitoBean
    private PageDescriptionService pageDescriptionService;

    @MockitoBean
    private TaxonomyService taxonomyService;




    @MockitoBean
    private TopicTreeSynthesizer topicTreeSynthesizer;

    @BeforeEach
    void stubIdentityTreeSynthesis() {
        when(topicTreeSynthesizer.synthesize(anyList(), anyString(), any())).thenAnswer(inv -> {
            List<String> outcomes = inv.getArgument(0);
            List<Integer> all = java.util.stream.IntStream.range(0, outcomes.size()).boxed().toList();
            if (all.isEmpty()) {
                return new TopicTreeSynthesizer.Plan(List.of(), List.of());
            }
            return new TopicTreeSynthesizer.Plan(List.of(new TopicTreeSynthesizer.PlannedTopic(
                    "Course Capability", List.of(), all)), List.of());
        });
    }

    /** One topic: groups of two or more become capabilities, single outcomes hang directly beneath it. */
    private void stubTopicPlan(String topicLabel, List<List<Integer>> groups) {
        stubTopicPlan(topicLabel, groups, List.of());
    }

    private void stubTopicPlan(String topicLabel, List<List<Integer>> groups, List<Integer> unmatched) {
        List<TopicTreeSynthesizer.PlannedCapability> capabilities = new java.util.ArrayList<>();
        List<Integer> direct = new java.util.ArrayList<>();
        for (List<Integer> group : groups) {
            if (group.size() < 2) {
                direct.addAll(group);
            } else {
                capabilities.add(new TopicTreeSynthesizer.PlannedCapability(
                        "Apply capability group " + (capabilities.size() + 1) + ".", group));
            }
        }
        when(topicTreeSynthesizer.synthesize(anyList(), anyString(), any())).thenReturn(
                new TopicTreeSynthesizer.Plan(List.of(
                        new TopicTreeSynthesizer.PlannedTopic(topicLabel, capabilities, direct)), unmatched));
    }

    private static ExtractedSkill skill(ExtractedGoal goal) {
        return skill(goal, 0, 0);
    }

    private static ExtractedSkill skill(ExtractedGoal goal, int sourceStartLine, int sourceEndLine) {
        return new ExtractedSkill(goal.text(), goal.shortLabel(), goal.kind(),
                BloomLevel.APPLY, SoloLevel.RELATIONAL,
                sourceStartLine, sourceEndLine, List.of());
    }

    private static ExtractedSkill figureSkill(String text, int sourceFigure) {
        return new ExtractedSkill(text, text, GoalKind.IMPLICIT, BloomLevel.APPLY, SoloLevel.RELATIONAL, null, null, sourceFigure, List.of());
    }

    private void startExtraction(UUID courseId) throws Exception {
        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/extract", courseId))
                .andExpect(status().isAccepted());
        awaitSucceeded(courseId);
    }

    private void startForcedExtraction(UUID courseId) throws Exception {
        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/extract?force=true", courseId))
                .andExpect(status().isAccepted());
        awaitSucceeded(courseId);
    }

    /**
     * The run is asynchronous now, so a pipeline exception no longer surfaces as a failed POST — it
     * lands on the tracker as FAILED. Assert the happy path here so a broken run fails loudly with
     * its own error rather than as a confusing empty-result assertion further down the test.
     */
    private void awaitSucceeded(UUID courseId) throws Exception {
        String body = awaitExtraction(courseId);
        assertThat(body).as("extraction for course %s should have succeeded", courseId)
                .contains("\"status\":\"SUCCEEDED\"");
    }

    /** For runs the test expects to fail — the guard rejections and audit-failure cases. */
    private void startExtractionExpectingFailure(UUID courseId) throws Exception {
        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/extract", courseId))
                .andExpect(status().isAccepted());
        awaitExtraction(courseId);
    }

    /** Waits for the run to reach a terminal state and returns the final status body. */
    private String awaitExtraction(UUID courseId) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            MvcResult result = mockMvc.perform(get("/learninggoalhub/api/courses/{id}/extract/status", courseId)).andReturn();
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
        UUID course = TestTenant.course("Extraction guard");
        PlacedGoal existing = GoalFixtures.goal(course, "Existing goal").create();

        startExtractionExpectingFailure(course);

        mockMvc.perform(get("/learninggoalhub/api/courses/{id}/extract/status", course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        verify(sessionExtractionService, never()).extract(anyString(), anyString(), anyString(), anyString(), any(), anyList(), anyInt(), any());
        assertThat(GoalFixtures.find(course, existing.id())).isPresent();
    }

    @Test
    void forcedExtractionClearsThePreviousRunBeforeBuildingItAgain() throws Exception {
        UUID course = TestTenant.course("Forced extraction");
        Document document = documentRepository.save(
                new Document(course, "forced.pdf", "application/pdf", "new outcome"));
        HierarchyNode oldModule = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Old module"));
        HierarchyNode oldSession = hierarchyRepository.save(
                new HierarchyNode(course, oldModule, HierarchyLevel.SESSION, "Old session", document));
        PlacedGoal oldGoal = GoalFixtures.goal(course, "Old goal").node(oldSession).create();
        goalSourceRepository.save(new GoalSource(oldGoal.id(), document, "old source"));

        when(sessionExtractionService.extract(eq("forced.pdf"), eq("new outcome"), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("New goal", "New goal", GoalKind.EXPLICIT, "new outcome"))));

        startForcedExtraction(course);

        assertThat(GoalFixtures.find(course, oldGoal.id())).isEmpty();
        assertThat(goalSourceRepository.findByIdGoalId(oldGoal.id())).isEmpty();
        assertThat(hierarchyRepository.findByCourseId(course))
                .noneMatch(node -> node.getLabel().equals("Old module") || node.getLabel().equals("Old session"));
        assertThat(GoalFixtures.goals(course))
                .filteredOn(goal -> goal.origin() == GoalOrigin.EXTRACTED)
                .extracting(PlacedGoal::text)
                .containsExactly("New goal");
    }

    @Test
    void extractionHoldsNoTransactionWhileTheModelsRun() throws Exception {
        UUID course = TestTenant.course("Forced extraction without a held transaction");
        Document document = documentRepository.save(
                new Document(course, "unlocked.pdf", "application/pdf", "new outcome"));
        PlacedGoal oldGoal = GoalFixtures.goal(course, "Old goal").create();
        AtomicLong openTransactions = new AtomicLong(-1);
        when(sessionExtractionService.extract(eq("unlocked.pdf"), anyString(), anyString(), anyString(), any(),
                anyList(), anyInt(), any())).thenAnswer(inv -> {
                    // Another connection that sits in an open transaction is the runner's: with a
                    // forced run it would hold the old goals' row locks until every call returned.
                    openTransactions.set(jdbcTemplate.queryForObject("""
                            select count(*) from pg_stat_activity
                            where datname = current_database() and pid <> pg_backend_pid()
                              and state like 'idle in transaction%'""", Long.class));
                    return List.of(skill(new ExtractedGoal("New goal", "New goal", GoalKind.EXPLICIT, "new outcome")));
                });

        startForcedExtraction(course);

        assertThat(openTransactions).hasValue(0);
        assertThat(GoalFixtures.find(course, oldGoal.id())).isEmpty();
        assertThat(GoalFixtures.goals(course))
                .filteredOn(goal -> goal.origin() == GoalOrigin.EXTRACTED)
                .extracting(PlacedGoal::text)
                .containsExactly("New goal");
        assertThat(goalSourceRepository.findByIdGoalId(GoalFixtures.goals(course).stream()
                .filter(goal -> goal.origin() == GoalOrigin.EXTRACTED)
                .findFirst().orElseThrow().id()))
                .singleElement()
                .satisfies(source -> assertThat(source.getDocument().getId()).isEqualTo(document.getId()));
    }

    /** The tree stage plans with the models too, so it must not hold a transaction across those calls either. */
    @Test
    void topicTreeStageHoldsNoTransactionWhileTheModelsRun() throws Exception {
        UUID course = TestTenant.course("Tree stage without a held transaction");
        documentRepository.save(new Document(course, "tree.pdf", "application/pdf", "Apply the capability."));
        when(sessionExtractionService.extract(eq("tree.pdf"), anyString(), anyString(), anyString(), any(),
                anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Capability", GoalKind.EXPLICIT,
                        "Apply the capability."))));
        AtomicLong openTransactions = stubTopicPlanCountingOpenTransactions("Tree Label");

        startExtraction(course);

        assertThat(openTransactions).hasValue(0);
        assertThat(GoalFixtures.goals(course))
                .filteredOn(g -> g.origin() == GoalOrigin.TERMINAL)
                .extracting(PlacedGoal::shortLabel)
                .containsExactly("Tree Label");
    }

    @Test
    void rebuildHoldsNoTransactionWhileTheModelsRun() throws Exception {
        UUID course = TestTenant.course("Rebuild without a held transaction");
        documentRepository.save(new Document(course, "rebuild.pdf", "application/pdf", "Apply the capability."));
        when(sessionExtractionService.extract(eq("rebuild.pdf"), anyString(), anyString(), anyString(), any(),
                anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Capability", GoalKind.EXPLICIT,
                        "Apply the capability."))));
        stubTopicPlan("First Label", List.of(List.of(0)));
        startExtraction(course);
        AtomicLong openTransactions = stubTopicPlanCountingOpenTransactions("Rebuilt Label");

        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/competency-tree", course))
                .andExpect(status().isOk());

        assertThat(openTransactions).hasValue(0);
        assertThat(GoalFixtures.goals(course))
                .filteredOn(g -> g.origin() == GoalOrigin.TERMINAL)
                .extracting(PlacedGoal::shortLabel)
                .containsExactly("Rebuilt Label");
    }

    /**
     * Plans one topic over the first outcome and records whether the planning call ran inside a
     * transaction. The call runs on the caller's thread, so the thread's own state is what counts.
     */
    private AtomicLong stubTopicPlanCountingOpenTransactions(String topicLabel) {
        AtomicLong openTransactions = new AtomicLong(-1);
        when(topicTreeSynthesizer.synthesize(anyList(), anyString(), any())).thenAnswer(inv -> {
            openTransactions.set(TransactionSynchronizationManager.isActualTransactionActive() ? 1 : 0);
            return new TopicTreeSynthesizer.Plan(List.of(
                    new TopicTreeSynthesizer.PlannedTopic(topicLabel, List.of(), List.of(0))), List.of());
        });
        return openTransactions;
    }

    @Test
    void aFailedForcedExtractionKeepsThePreviousOne() throws Exception {
        UUID course = TestTenant.course("Failed forced extraction");
        Document document = documentRepository.save(
                new Document(course, "failing.pdf", "application/pdf", "new outcome"));
        HierarchyNode oldModule = hierarchyRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.MODULE, "Old module"));
        PlacedGoal oldGoal = GoalFixtures.goal(course, "Old goal").node(hierarchyRepository.save(
                new HierarchyNode(course, oldModule, HierarchyLevel.SESSION, "Old session", document))).create();
        when(sessionExtractionService.extract(eq("failing.pdf"), anyString(), anyString(), anyString(), any(),
                anyList(), anyInt(), any())).thenThrow(new IllegalStateException("HTTP 503"));

        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/extract?force=true", course))
                .andExpect(status().isAccepted());
        assertThat(awaitExtraction(course)).contains("\"status\":\"FAILED\"");

        assertThat(GoalFixtures.find(course, oldGoal.id())).isPresent();
        assertThat(hierarchyRepository.findByCourseId(course)).extracting(HierarchyNode::getLabel)
                .containsExactlyInAnyOrder("Old module", "Old session");
    }

    @Test
    void extractionPersistsGoalsAndSourcesPerDocument() throws Exception {
        UUID course = TestTenant.course("Software Engineering");
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture text about TDD"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise on refactoring"));

        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture text about TDD"), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any())).thenReturn(List.of(
                skill(new ExtractedGoal("Apply test-driven development.", "Test-Driven Development", GoalKind.EXPLICIT,
                        "...write a failing test first..."), 99, 99),
                skill(new ExtractedGoal("Value short feedback loops.", "Feedback Loops", GoalKind.IMPLICIT,
                        "...keep tests fast..."))
        ));
        when(sessionExtractionService.extract(eq("exercise.pdf"), eq("exercise on refactoring"), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any())).thenReturn(List.of(
                skill(new ExtractedGoal("Refactor without changing behaviour.", "Behaviour-Preserving Refactoring",
                        GoalKind.EXPLICIT, "...extract method..."))
        ));

        startExtraction(course);

        List<PlacedGoal> goals = GoalFixtures.goals(course).stream()
                .filter(goal -> goal.origin() == GoalOrigin.EXTRACTED)
                .toList();
        assertThat(goals).hasSize(3);
        assertThat(goals).extracting(PlacedGoal::kind)
                .containsExactlyInAnyOrder(GoalKind.EXPLICIT, GoalKind.IMPLICIT, GoalKind.EXPLICIT);
        assertThat(goals).extracting(PlacedGoal::shortLabel)
                .containsExactlyInAnyOrder("Test-Driven Development", "Feedback Loops",
                        "Behaviour-Preserving Refactoring");

        long sourcesForLecture = goalSourceRepository.findAll().stream()
                .filter(s -> s.getDocument().getId().equals(lecture.getId()))
                .count();
        long sourcesForExercise = goalSourceRepository.findAll().stream()
                .filter(s -> s.getDocument().getId().equals(exercise.getId()))
                .count();
        assertThat(sourcesForLecture).isEqualTo(2);
        assertThat(sourcesForExercise).isEqualTo(1);

        PlacedGoal fabricatedGoal = goals.stream()
                .filter(g -> g.text().equals("Apply test-driven development."))
                .findFirst()
                .orElseThrow();
        assertThat(goalSourceRepository.findByIdGoalId(fabricatedGoal.id()))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.getSnippet()).isEmpty();
                    assertThat(source.getPage()).isNull();
                    assertThat(source.getGroundingQuality()).isEqualTo(SourceMatchQuality.NONE);
                    assertThat(source.isGrounded()).isFalse();
                    assertThat(source.getUnverifiedSnippet()).isNull();
                });

        PlacedGoal exactGoal = goals.stream()
                .filter(g -> g.text().equals("Value short feedback loops."))
                .findFirst()
                .orElseThrow();
        assertThat(goalSourceRepository.findByIdGoalId(exactGoal.id()))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.getSnippet()).isEqualTo("lecture text about TDD");
                    assertThat(source.getGroundingQuality()).isEqualTo(SourceMatchQuality.EXACT_IN_SESSION);
                    assertThat(source.isGrounded()).isTrue();
                });

        ExtractionRun run = extractionRunRepository.findByCourseId(course).stream()
                .findFirst()
                .orElseThrow();
        assertThat(run.getStatus()).isEqualTo(ExtractionStatus.SUCCEEDED);
        assertThat(run.getPromptVersion()).isEqualTo(SessionExtractionService.PROMPT_VERSION);
        assertThat(run.getGoalsCreated()).isEqualTo(3);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getParams()).contains("unit-max-chars").contains("\"skill-target-chars\"")
                .contains("exercise-skill-target-chars");
    }

    @Test
    void multiPdfExtractionUsesNaturalLectureOrderInsteadOfUploadOrder() throws Exception {
        UUID course = TestTenant.course("Naturally ordered lectures");
        documentRepository.save(new Document(course, "Lecture 10.pdf", "application/pdf", "later material"));
        documentRepository.save(new Document(course, "Lecture 2.pdf", "application/pdf", "earlier material"));
        when(sessionExtractionService.extract(eq("Lecture 10.pdf"), eq("later material"),
                eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal(
                        "Applying the later method in representative contexts.", "Apply Later Method",
                        GoalKind.IMPLICIT, "later material"))));
        when(sessionExtractionService.extract(eq("Lecture 2.pdf"), eq("earlier material"),
                eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal(
                        "Applying the earlier method in representative contexts.", "Apply Earlier Method",
                        GoalKind.IMPLICIT, "earlier material"))));

        startExtraction(course);

        Map<String, Integer> orderByLabel = GoalFixtures.goals(course).stream()
                .filter(goal -> goal.origin() == GoalOrigin.EXTRACTED)
                .collect(Collectors.toMap(PlacedGoal::shortLabel, PlacedGoal::lectureOrder));
        assertThat(orderByLabel.get("Apply Earlier Method"))
                .isLessThan(orderByLabel.get("Apply Later Method"));
    }

    @Test
    void auditServicePersistsRunningThenSucceededInSeparateTransactions() {
        UUID course = TestTenant.course("Audit lifecycle");

        Long runId = extractionRunAuditService.start(course, null, "direct-v1",
                "{\"unit-max-chars\":12000,\"skill-target-chars\":3000,\"parallelism\":16}");
        assertThat(extractionRunRepository.findById(runId).orElseThrow().getStatus())
                .isEqualTo(ExtractionStatus.RUNNING);

        extractionRunAuditService.finish(runId, ExtractionStatus.SUCCEEDED, null, 4, 1, 3, "direct-v1");

        ExtractionRun run = extractionRunRepository.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(ExtractionStatus.SUCCEEDED);
        assertThat(run.getGoalsCreated()).isEqualTo(4);
        assertThat(run.getFailedSessions()).isEqualTo(1);
        assertThat(run.getDroppedOutcomes()).isEqualTo(3);
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void directSourcesPreferLinesThenUseFiguresAndRejectInvalidFigureIndices() throws Exception {
        UUID course = TestTenant.course("Figure source precedence");
        // Figure evidence is opt-in per course, and this test is about what it does once opted in.
        settingsService.settings(course).setFiguresEnabled(true);
        settingsRepository.flush();
        String rawText = "verbatim line";
        Document document = documentRepository.save(
                new Document(course, "figures.pdf", "application/pdf", rawText));
        document.setPageOffsets(new int[]{0, rawText.length(), rawText.length()});
        documentRepository.saveAndFlush(document);
        documentSectionRepository.saveAndFlush(new DocumentSection(
                document, 0, "Section", 0, rawText.length(), 1, 2));
        pageDescriptionRepository.saveAndFlush(
                new PageDescription(document, 2, "A diagram teaches the process.", "vision-test"));

        when(sessionExtractionService.extract(eq("Section"), eq(rawText), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(
                        new ExtractedSkill("Text outcome", "Text", GoalKind.IMPLICIT,
                                BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, 0, List.of()),
                        figureSkill("Figure outcome", 0),
                        figureSkill("Unsupported outcome", 4)));

        startExtraction(course);

        Map<UUID, String> goalTextById = GoalFixtures.goals(course).stream()
                .collect(Collectors.toMap(PlacedGoal::id, PlacedGoal::text));
        Map<String, GoalSource> sources = goalSourceRepository.findAll().stream()
                .filter(source -> source.getDocument().getId().equals(document.getId()))
                .collect(Collectors.toMap(source -> goalTextById.get(source.getGoalId()), source -> source));
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
        UUID course = TestTenant.course("Software Engineering");
        // unit-max-chars is 80 here, so a 180-character document cuts into three 60-character
        // line-aligned windows.
        String first = paddedLine("Apply the first capability.");
        String second = paddedLine("Apply the second capability.");
        String third = paddedLine("Apply the third capability.");
        Document document = documentRepository.save(
                new Document(course, "combined.pdf", "application/pdf", first + second + third));

        when(sessionExtractionService.extract(eq("combined.pdf"), eq(first), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the first capability.", "First",
                        GoalKind.EXPLICIT, ""), 0, 0)));
        when(sessionExtractionService.extract(eq("combined.pdf"), eq(second), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the second capability.", "Second",
                        GoalKind.EXPLICIT, ""), 0, 0)));
        when(sessionExtractionService.extract(eq("combined.pdf"), eq(third), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the third capability.", "Third",
                        GoalKind.EXPLICIT, ""), 0, 0)));

        startExtraction(course);

        List<PlacedGoal> extracted = GoalFixtures.goals(course).stream()
                .filter(goal -> goal.origin() == GoalOrigin.EXTRACTED)
                .toList();
        assertThat(extracted).extracting(PlacedGoal::shortLabel)
                .containsExactlyInAnyOrder("First", "Second", "Third");

        // The split is invisible above the extraction unit: one session node owns all three windows.
        assertThat(extracted).extracting(goal -> goal.hierarchyNode().getId())
                .containsOnly(extracted.getFirst().hierarchyNode().getId());
        assertThat(hierarchyRepository.findByCourseId(course))
                .filteredOn(node -> node.getLevel() == HierarchyLevel.SESSION)
                .singleElement()
                .satisfies(node -> assertThat(node.getLabel()).isEqualTo("combined.pdf"));

        Map<UUID, String> goalTextById = extracted.stream()
                .collect(Collectors.toMap(PlacedGoal::id, PlacedGoal::text));
        Map<String, String> snippets = goalSourceRepository.findAll().stream()
                .filter(source -> source.getDocument().getId().equals(document.getId()))
                .collect(Collectors.toMap(source -> goalTextById.get(source.getGoalId()),
                        source -> source.getSnippet().strip()));
        assertThat(snippets).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Apply the first capability.", "Apply the first capability.",
                "Apply the second capability.", "Apply the second capability.",
                "Apply the third capability.", "Apply the third capability."));

        assertThat(extractionRunRepository.findByCourseId(course))
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
        UUID course = TestTenant.course("Paged sections");
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
                    eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                    .thenReturn(List.of(skill(new ExtractedGoal(labels.get(i) + " outcome", labels.get(i),
                            GoalKind.EXPLICIT, ""), 0, 0)));
        }

        startExtraction(course);

        List<PlacedGoal> extracted = GoalFixtures.goals(course).stream()
                .filter(goal -> goal.origin() == GoalOrigin.EXTRACTED)
                .toList();
        assertThat(extracted).extracting(PlacedGoal::shortLabel)
                .containsExactlyInAnyOrderElementsOf(labels);
        assertThat(extracted).extracting(goal -> goal.hierarchyNode().getId())
                .containsOnly(extracted.getFirst().hierarchyNode().getId());
        assertThat(hierarchyRepository.findByCourseId(course))
                .filteredOn(node -> node.getLevel() == HierarchyLevel.SESSION)
                .singleElement()
                .satisfies(node -> assertThat(node.getLabel()).isEqualTo("Combined chapter"));

        Map<UUID, String> goalTextById = extracted.stream()
                .collect(Collectors.toMap(PlacedGoal::id, PlacedGoal::text));
        Map<String, Integer> pageByGoal = goalSourceRepository.findAll().stream()
                .filter(source -> source.getDocument().getId().equals(document.getId()))
                .collect(Collectors.toMap(source -> goalTextById.get(source.getGoalId()),
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
        UUID course = TestTenant.course("Terminal competency labels");
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        stubTopicPlan("Terminal Capability", List.of(List.of(0)));

        startExtraction(course);

        assertThat(GoalFixtures.goals(course))
                .filteredOn(g -> g.origin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(PlacedGoal::shortLabel)
                .isEqualTo("Terminal Capability");
    }

    @Test
    void topicGroupsItsSkillsUnderGeneratedCapabilities() throws Exception {
        UUID course = TestTenant.course("Consolidated competency");
        String material = "Apply one. Apply two. Apply three. Apply four. Apply five. Apply six.";
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", material));
        List<ExtractedSkill> extracted = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> skill(new ExtractedGoal(
                        "Apply capability " + index + ".", "Capability " + index,
                        GoalKind.EXPLICIT, "...capability..."), index - 1, index - 1))
                .toList();
        when(sessionExtractionService.extract(eq("session.pdf"), eq(material), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(extracted);
        when(taxonomyService.classifyBatch(anyList(), eq(null))).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream()
                    .map(text -> new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL))
                    .toList();
        });
        stubTopicPlan("Course Methods", List.of(List.of(0, 1, 2), List.of(3, 4, 5)));

        startExtraction(course);

        List<PlacedGoal> goals = GoalFixtures.goals(course);
        PlacedGoal terminal = goals.stream()
                .filter(goal -> goal.origin() == GoalOrigin.TERMINAL)
                .findFirst().orElseThrow();
        assertThat(terminal.text()).isEqualTo("Course Methods");
        // Resolve through the repository: the edge's source is a lazy proxy and this runs outside
        // a session, so navigating it directly would throw.
        List<PlacedGoal> capabilities = GoalFixtures.edgesInto(terminal.id()).stream()
                .map(relationship -> GoalFixtures.goal(relationship.sourceGoalId()))
                .toList();
        // Each group becomes a generated capability without a source or a hierarchy node.
        assertThat(capabilities).hasSize(2).allSatisfy(node -> {
            assertThat(node.origin()).isEqualTo(GoalOrigin.SYNTHESIZED);
            assertThat(node.role()).isEqualTo(GoalRole.SKILL);
            assertThat(node.hierarchyNode()).isNull();
            assertThat(goalSourceRepository.findByIdGoalId(node.id())).isEmpty();
        });
        // Every extracted skill stays a node of its own beneath its capability.
        assertThat(capabilities).allSatisfy(node ->
                assertThat(GoalFixtures.edgesInto(node.id()))
                        .hasSize(3)
                        .allSatisfy(relationship -> {
                            assertThat(relationship.type()).isEqualTo(RelationshipType.CONTRIBUTES_TO.name());
                            assertThat(GoalFixtures.goal(relationship.sourceGoalId())
                                    .origin()).isEqualTo(GoalOrigin.EXTRACTED);
                        }));
        assertThat(goals).filteredOn(goal -> goal.origin() == GoalOrigin.EXTRACTED).hasSize(6);
    }

    /**
     * A goal the assignment step could not place must still reach the tree. The client only renders
     * goals reachable from a terminal, so leaving it unlinked would make it invisible — the catch-all
     * keeps it visible without asserting it belongs to a competency it does not serve.
     */
    @Test
    void compactPlanCoversEverySourceWithoutCatchAllTerminal() throws Exception {
        UUID course = TestTenant.course("Unmatched goals");
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        // The model placed the only goal nowhere.
        stubTopicPlan("Covered Capability", List.of(List.of(0)));

        startExtraction(course);

        List<PlacedGoal> terminals = GoalFixtures.goals(course).stream()
                .filter(g -> g.origin() == GoalOrigin.TERMINAL)
                .toList();
        assertThat(terminals).singleElement()
                .extracting(PlacedGoal::shortLabel)
                .isEqualTo("Covered Capability");
        PlacedGoal extracted = GoalFixtures.goals(course).stream()
                .filter(g -> g.origin() == GoalOrigin.EXTRACTED)
                .findFirst()
                .orElseThrow();
        // A lone outcome forms no capability, so it contributes to the topic directly.
        assertThat(GoalFixtures.edgesFrom(extracted.id()))
                .singleElement()
                .satisfies(relationship -> {
                    assertThat(relationship.type()).isEqualTo(RelationshipType.CONTRIBUTES_TO.name());
                    assertThat(GoalFixtures.goal(relationship.targetGoalId())
                            .origin()).isEqualTo(GoalOrigin.TERMINAL);
                });
    }

    @Test
    void unmatchedGoalsReceiveSemanticCompetencyRepairBeforeCatchAll() throws Exception {
        UUID course = TestTenant.course("Semantic coverage repair");
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));
        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."),
                eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal(
                        "Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        stubTopicPlan("Repaired Capability", List.of(List.of(0)));

        startExtraction(course);

        assertThat(GoalFixtures.goals(course))
                .filteredOn(goal -> goal.origin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(PlacedGoal::shortLabel)
                .isEqualTo("Repaired Capability");
    }

    /** A skill no topic covers stays out of the tree, and the rebuild reports how many there were. */
    @Test
    void skillsNoTopicCoversStayOutOfTheTree() throws Exception {
        UUID course = TestTenant.course("Unmatched skill");
        String material = "Apply one. Apply two.";
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", material));
        when(sessionExtractionService.extract(eq("session.pdf"), eq(material), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(
                        skill(new ExtractedGoal("Apply capability 1.", "Capability 1", GoalKind.EXPLICIT, "...one..."), 0, 0),
                        skill(new ExtractedGoal("Apply capability 2.", "Capability 2", GoalKind.EXPLICIT, "...two..."), 0, 0)));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        stubTopicPlan("Covered", List.of(List.of(0)), List.of(1));

        startExtraction(course);
        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/competency-tree", course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competencies").value(1))
                .andExpect(jsonPath("$.unmatchedGoals").value(1));

        PlacedGoal unmatched = GoalFixtures.goals(course).stream()
                .filter(g -> "Apply capability 2.".equals(g.text()))
                .findFirst()
                .orElseThrow();
        assertThat(GoalFixtures.edgesFrom(unmatched.id())).isEmpty();
    }

    /**
     * The naming call may propose a competency the assignment then gives nothing. The course does not
     * build toward it, so it must not reach the tree as a childless top-level node.
     */
    @Test
    void competenciesThatReceiveNoGoalsAreNotPersisted() throws Exception {
        UUID course = TestTenant.course("Empty competency");
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        // Everything lands on the first competency; the second is left empty.
        stubTopicPlan("Claimed", List.of(List.of(0)));

        startExtraction(course);

        assertThat(GoalFixtures.goals(course))
                .filteredOn(g -> g.origin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(PlacedGoal::shortLabel)
                .isEqualTo("Claimed");
    }

    /**
     * The rebuild exists so the tree can be iterated on without re-reading documents: it replaces the
     * terminals and their edges while the extracted goals stay exactly where they were.
     */
    @Test
    void rebuildReplacesTheTreeAndKeepsExtractedGoals() throws Exception {
        UUID course = TestTenant.course("Rebuild");
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        stubTopicPlan("First Label", List.of(List.of(0)));

        startExtraction(course);
        List<UUID> extractedIdsBefore = GoalFixtures.goals(course).stream()
                .filter(g -> g.origin() == GoalOrigin.EXTRACTED)
                .map(PlacedGoal::id)
                .toList();
        goalApi.update(extractedIdsBefore.getFirst(),
                goalApi.goal(extractedIdsBefore.getFirst()).asChange().withRole(null));
        PlacedGoal legacySkill = GoalFixtures.goal(extractedIdsBefore.getFirst());
        PlacedGoal legacyKnowledge = GoalFixtures.goal(course, "Explain the legacy basics.")
                .bloom(BloomLevel.UNDERSTAND.name()).node(legacySkill.hierarchyNode()).create();
        GoalFixtures.contributes(legacyKnowledge, legacySkill);
        extractedIdsBefore = java.util.stream.Stream.concat(extractedIdsBefore.stream(),
                java.util.stream.Stream.of(legacyKnowledge.id())).toList();

        stubTopicPlan("Second Label", List.of(List.of(0)));

        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/competency-tree", course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.competencies").value(1))
                .andExpect(jsonPath("$.unmatchedGoals").value(0));

        assertThat(GoalFixtures.goals(course))
                .filteredOn(g -> g.origin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(PlacedGoal::shortLabel)
                .isEqualTo("Second Label");
        assertThat(GoalFixtures.goals(course).stream()
                .filter(g -> g.origin() == GoalOrigin.EXTRACTED
                        || "Explain the legacy basics.".equals(g.text()))
                .map(PlacedGoal::id))
                .containsExactlyInAnyOrderElementsOf(extractedIdsBefore);
        assertThat(hierarchyRepository.findByCourseId(course))
                .filteredOn(n -> n.getLevel() == HierarchyLevel.COMPETENCY)
                .hasSize(1);
        assertThat(GoalFixtures.goal(legacySkill.id()).role()).isNull();
        assertThat(GoalFixtures.edgesFrom(legacyKnowledge.id())).isEmpty();
    }

    /**
     * Rebuilding twice must produce the same tree, not a thicker one. With elected sub-skills both
     * ends of a tree edge are surviving extracted goals, so a rebuild that failed to delete its own
     * edges would stack a second copy on every run and silently inflate each group.
     */
    @Test
    void repeatedRebuildsDoNotAccumulateTreeEdges() throws Exception {
        UUID course = TestTenant.course("Repeated rebuild");
        String material = "Apply one. Apply two. Apply three. Apply four. Apply five. Apply six.";
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", material));
        List<ExtractedSkill> extracted = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> skill(new ExtractedGoal(
                        "Apply capability " + index + ".", "Capability " + index,
                        GoalKind.EXPLICIT, "...capability..."), index - 1, index - 1))
                .toList();
        when(sessionExtractionService.extract(eq("session.pdf"), eq(material), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(extracted);
        when(taxonomyService.classifyBatch(anyList(), eq(null))).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream()
                    .map(text -> new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL))
                    .toList();
        });
        stubTopicPlan("Course Methods", List.of(List.of(0, 1, 2), List.of(3, 4, 5)));
        startExtraction(course);

        List<UUID> courseGoalIds = GoalFixtures.goals(course).stream()
                .map(PlacedGoal::id).toList();
        int afterExtraction = GoalFixtures.edgesFromAny(courseGoalIds).size();

        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/competency-tree?force=true", course))
                .andExpect(status().isOk());
        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/competency-tree?force=true", course))
                .andExpect(status().isOk());

        List<UUID> rebuiltGoalIds = GoalFixtures.goals(course).stream()
                .map(PlacedGoal::id).toList();
        assertThat(GoalFixtures.edgesFromAny(rebuiltGoalIds))
                .as("two further rebuilds must not add edges")
                .hasSize(afterExtraction);
        assertThat(GoalFixtures.goals(course))
                .filteredOn(goal -> goal.origin() == GoalOrigin.EXTRACTED)
                .hasSize(6);
    }

    /** Extraction creates knowledge → skill edges, and rebuilding preserves them while replacing tree edges. */
    @Test
    void rebuildLeavesNoEdgesFromThePreviousTree() throws Exception {
        UUID course = TestTenant.course("Rebuild edges");
        documentRepository.save(new Document(course, "session.pdf", "application/pdf",
                "Apply the capability.\n\nUnderstand the basics."));

        when(sessionExtractionService.extract(eq("session.pdf"), anyString(), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(
                        new ExtractedSkill("Apply the capability.", "Capability", GoalKind.EXPLICIT,
                                BloomLevel.APPLY, SoloLevel.RELATIONAL, 0, 0, List.of(new ExtractedSkill.Knowledge(
                                        "Understand the basics.", "Basics", GoalKind.EXPLICIT, BloomLevel.UNDERSTAND, SoloLevel.RELATIONAL, 1, 1)))));
        when(taxonomyService.classifyBatch(anyList(), eq(null))).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream()
                    .map(t -> t.startsWith("Understand")
                            ? new TaxonomyClassification(BloomLevel.UNDERSTAND, SoloLevel.UNISTRUCTURAL)
                            : new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL))
                    .toList();
        });
        stubTopicPlan("Capability", List.of(List.of(0)));

        startExtraction(course);

        PlacedGoal knowledgeBefore = GoalFixtures.goals(course).stream()
                .filter(g -> "Understand the basics.".equals(g.text()))
                .findFirst()
                .orElseThrow();
        PlacedGoal skillBefore = GoalFixtures.goals(course).stream()
                .filter(g -> "Apply the capability.".equals(g.text()))
                .findFirst()
                .orElseThrow();
        assertThat(knowledgeBefore.role()).isEqualTo(GoalRole.KNOWLEDGE);
        assertThat(skillBefore.role()).isEqualTo(GoalRole.SKILL);
        assertThat(goalSourceRepository.findByIdGoalId(skillBefore.id()))
                .singleElement()
                .extracting(GoalSource::getSnippet)
                .isEqualTo("Apply the capability.");
        assertThat(goalSourceRepository.findByIdGoalId(knowledgeBefore.id()))
                .singleElement()
                .extracting(GoalSource::getSnippet)
                .isEqualTo("Understand the basics.");
        assertThat(GoalFixtures.edgesFrom(knowledgeBefore.id()))
                .extracting(r -> r.targetGoalId())
                .contains(skillBefore.id());

        stubTopicPlan("Rebuilt Capability", List.of(List.of(0)));
        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/competency-tree", course))
                .andExpect(status().isOk());

        PlacedGoal knowledge = GoalFixtures.goals(course).stream()
                .filter(g -> "Understand the basics.".equals(g.text()))
                .findFirst()
                .orElseThrow();
        PlacedGoal skill = GoalFixtures.goals(course).stream()
                .filter(g -> "Apply the capability.".equals(g.text()))
                .findFirst()
                .orElseThrow();
        // Compare ids, not text: the edge's target is a lazy proxy and this runs outside a session.
        assertThat(GoalFixtures.edgesFrom(knowledge.id()))
                .as("the extraction edge must survive the rebuild")
                .extracting(r -> r.targetGoalId())
                .contains(skill.id());
        // The lone extracted skill is elected as its own group's sub-skill, so after the rebuild it
        // hangs off the fresh terminal directly rather than supporting a generated node.
        assertThat(GoalFixtures.edgesFrom(skill.id()))
                .singleElement()
                .satisfies(relationship -> assertThat(relationship.type())
                        .isEqualTo(RelationshipType.CONTRIBUTES_TO.name()));
    }

    /**
     * A rebuild synthesises the replacement before it destroys anything, so a model outage mid-rebuild
     * cannot leave the course with its tree deleted and nothing in its place.
     */
    @Test
    void failedRebuildLeavesTheExistingTreeIntact() throws Exception {
        UUID course = TestTenant.course("Rebuild failure");
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        stubTopicPlan("Survivor", List.of(List.of(0)));

        startExtraction(course);

        // The model goes down before the rebuild can synthesise a replacement.
        when(topicTreeSynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenThrow(new IllegalStateException("model unavailable"));

        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/competency-tree", course))
                .andExpect(status().isBadGateway());

        assertThat(GoalFixtures.goals(course))
                .filteredOn(g -> g.origin() == GoalOrigin.TERMINAL)
                .singleElement()
                .extracting(PlacedGoal::shortLabel)
                .isEqualTo("Survivor");
    }

    /**
     * A rebuild replaces exactly the nodes an instructor may have approved or typed, and nothing
     * records what they were — so it refuses rather than destroying them silently.
     */
    @Test
    void rebuildRefusesWhenTheTreeHoldsApprovedWork() throws Exception {
        UUID course = TestTenant.course("Rebuild guard");
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));

        startExtraction(course);

        PlacedGoal terminal = GoalFixtures.goals(course).stream()
                .filter(g -> g.origin() == GoalOrigin.TERMINAL)
                .findFirst()
                .orElseThrow();
        goalApi.setStatus(terminal.id(), de.tum.cit.hestia.core.GoalStatus.APPROVED);

        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/competency-tree", course))
                .andExpect(status().isConflict());
        assertThat(GoalFixtures.goals(course))
                .anyMatch(g -> g.origin() == GoalOrigin.TERMINAL);

        // The override is what makes the destruction deliberate.
        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/competency-tree?force=true", course))
                .andExpect(status().isOk());
        assertThat(GoalFixtures.goals(course))
                .filteredOn(g -> g.origin() == GoalOrigin.TERMINAL)
                .singleElement()
                .satisfies(g -> assertThat(g.status())
                        .isEqualTo(de.tum.cit.hestia.core.GoalStatus.PENDING));
    }

    /**
     * Without a single placement the tree would be one bucket holding the whole course, which is
     * worse than no tree. Nothing may be persisted in that case — not even the competency root.
     */
    @Test
    void failedTerminalSynthesisKeepsExtractedGoalsForTreeOnlyRetry() throws Exception {
        UUID course = TestTenant.course("Terminal synthesis failure");
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."),
                eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal(
                        "Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        when(topicTreeSynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenThrow(new IllegalStateException("HTTP 500 - No response body available"));

        startExtractionExpectingFailure(course);

        String status = awaitExtraction(course);
        assertThat(status)
                .contains("\"status\":\"FAILED\"")
                .contains("The learning goals were saved")
                .contains("Topic tree synthesis failed")
                .contains("HTTP 500");
        assertThat(GoalFixtures.goals(course))
                .singleElement()
                .satisfies(goal -> assertThat(goal.origin()).isEqualTo(GoalOrigin.EXTRACTED));
        assertThat(hierarchyRepository.existsByCourseIdAndLevel(course, HierarchyLevel.MODULE)).isTrue();
        assertThat(hierarchyRepository.existsByCourseIdAndLevel(course, HierarchyLevel.COMPETENCY)).isFalse();

        stubTopicPlan("Apply Course Capability", List.of(List.of(0)));
        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/competency-tree", course))
                .andExpect(status().isOk());

        assertThat(awaitExtraction(course)).contains("\"status\":\"SUCCEEDED\"");
        assertThat(hierarchyRepository.existsByCourseIdAndLevel(course, HierarchyLevel.COMPETENCY)).isTrue();
        assertThat(GoalFixtures.goals(course)).filteredOn(g -> g.origin() == GoalOrigin.EXTRACTED)
                .hasSize(1);
    }

    @Test
    void failedAssignmentKeepsExtractedGoalsForTreeOnlyRetry() throws Exception {
        UUID course = TestTenant.course("Assignment failure");
        documentRepository.save(new Document(course, "session.pdf", "application/pdf", "Apply the capability."));

        when(sessionExtractionService.extract(eq("session.pdf"), eq("Apply the capability."), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal("Apply the capability.", "Source Capability", GoalKind.EXPLICIT,
                        "...capability..."))));
        when(taxonomyService.classifyBatch(anyList(), eq(null)))
                .thenReturn(List.of(new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.RELATIONAL)));
        when(topicTreeSynthesizer.synthesize(anyList(), eq("English"), eq(null)))
                .thenThrow(new IllegalStateException("assignment call failed"));

        startExtractionExpectingFailure(course);

        String status = awaitExtraction(course);
        assertThat(status)
                .contains("\"status\":\"FAILED\"")
                .contains("Topic tree synthesis failed")
                .contains("assignment call failed");
        assertThat(GoalFixtures.goals(course))
                .singleElement()
                .satisfies(goal -> assertThat(goal.origin()).isEqualTo(GoalOrigin.EXTRACTED));
        assertThat(hierarchyRepository.existsByCourseIdAndLevel(course, HierarchyLevel.MODULE)).isTrue();
        assertThat(hierarchyRepository.existsByCourseIdAndLevel(course, HierarchyLevel.COMPETENCY)).isFalse();
        assertThat(extractionRunRepository.findByCourseId(course))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.getStatus()).isEqualTo(ExtractionStatus.FAILED);
                    assertThat(run.getError()).contains("Topic tree synthesis failed");
                    assertThat(run.getGoalsCreated()).isEqualTo(1);
                });
    }

    @Test
    void failedSessionFailsTheRunWithoutPublishingPartialGoals() throws Exception {
        UUID course = TestTenant.course("Software Engineering");
        String failing = "short session text";
        String healthy = "healthy session text";
        documentRepository.save(new Document(course, "failed.pdf", "application/pdf", failing));
        documentRepository.save(new Document(course, "healthy.pdf", "application/pdf", healthy));
        when(sessionExtractionService.extract(eq("failed.pdf"), eq(failing), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenThrow(new RuntimeException("direct extraction failed"));
        when(sessionExtractionService.extract(eq("healthy.pdf"), eq(healthy), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any()))
                .thenReturn(List.of(skill(new ExtractedGoal(
                        "Apply test-driven development.", GoalKind.EXPLICIT, "...healthy snippet..."))));

        startExtractionExpectingFailure(course);

        String status = awaitExtraction(course);
        assertThat(status)
                .contains("\"status\":\"FAILED\"")
                .contains("\"failedSessions\":1")
                .contains("\"failedSessionNames\":[\"failed.pdf\"]");
        mockMvc.perform(get("/learninggoalhub/api/courses/{id}", course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.extractionStatus").value("FAILED"));
        assertThat(GoalFixtures.goals(course)).isEmpty();
        assertThat(extractionRunRepository.findByCourseId(course))
                .singleElement()
                .satisfies(run -> {
                    assertThat(run.getStatus()).isEqualTo(ExtractionStatus.FAILED);
                    assertThat(run.getError()).contains("failed.pdf");
                    assertThat(run.getFinishedAt()).isNotNull();
                    assertThat(run.getGoalsCreated()).isNull();
                    assertThat(run.getFailedSessions()).isEqualTo(1);
                });
    }

    @Test
    void identicalGoalsAcrossDocumentsRemainSeparateGoals() throws Exception {
        UUID course = TestTenant.course("Software Engineering");
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture body"));
        Document exercise = documentRepository.save(new Document(course, "exercise.pdf", "application/pdf", "exercise body"));

        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture body"), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any())).thenReturn(List.of(
                skill(new ExtractedGoal("Apply test-driven development.", GoalKind.EXPLICIT, "...lecture snippet..."))
        ));
        when(sessionExtractionService.extract(eq("exercise.pdf"), eq("exercise body"), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any())).thenReturn(List.of(
                skill(new ExtractedGoal("Apply TDD when writing code.", GoalKind.EXPLICIT, "...exercise snippet..."))
        ));

        startExtraction(course);

        List<PlacedGoal> goals = GoalFixtures.goals(course).stream()
                .filter(goal -> goal.origin() == GoalOrigin.EXTRACTED)
                .toList();
        assertThat(goals).hasSize(2);
        assertThat(goals)
                .flatExtracting(goal -> goalSourceRepository.findByIdGoalId(goal.id()))
                .extracting(s -> s.getDocument().getId())
                .containsExactlyInAnyOrder(lecture.getId(), exercise.getId());
    }

    @Test
    void duplicateExtractionsFromSameDocumentCreateSeparateGoals() throws Exception {
        UUID course = TestTenant.course("Software Engineering");
        Document lecture = documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture body"));

        // A direct response can contain closely related goals; each enriched goal is persisted.
        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture body"), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any())).thenReturn(List.of(
                skill(new ExtractedGoal("Apply TDD.", GoalKind.EXPLICIT, "...first snippet...")),
                skill(new ExtractedGoal("Apply TDD (rephrased).", GoalKind.EXPLICIT, "...second snippet..."))
        ));

        startExtraction(course);

        List<PlacedGoal> goals = GoalFixtures.goals(course).stream()
                .filter(goal -> goal.origin() == GoalOrigin.EXTRACTED)
                .toList();
        assertThat(goals).hasSize(2);
        assertThat(goals)
                .flatExtracting(goal -> goalSourceRepository.findByIdGoalId(goal.id()))
                .hasSize(2)
                .allSatisfy(s -> assertThat(s.getDocument().getId()).isEqualTo(lecture.getId()));
    }

    @Test
    void unknownCourseReturns404() throws Exception {
        mockMvc.perform(post("/learninggoalhub/api/courses/{id}/extract", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void statusReturnsNoContentBeforeAnyRun() throws Exception {
        UUID course = TestTenant.course("Untouched");

        mockMvc.perform(get("/learninggoalhub/api/courses/{id}/extract/status", course))
                .andExpect(status().isNoContent());
    }

    @Test
    void statusReportsSucceededWithSummaryAfterExtraction() throws Exception {
        UUID course = TestTenant.course("Software Engineering");
        documentRepository.save(new Document(course, "lecture.pdf", "application/pdf", "lecture text about TDD"));

        when(sessionExtractionService.extract(eq("lecture.pdf"), eq("lecture text about TDD"), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any())).thenReturn(List.of(
                skill(new ExtractedGoal("Apply test-driven development.", GoalKind.EXPLICIT, "...failing test first..."))
        ));

        startExtraction(course);

        mockMvc.perform(get("/learninggoalhub/api/courses/{id}/extract/status", course))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.phase").value("SYNTHESIZING"))
                .andExpect(jsonPath("$.percent").value(100))
                .andExpect(jsonPath("$.summary.goalsCreated").value(1))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void extractionSplitsOneDocumentIntoItsStructuralSectionsAndRoutesChunksByOffset() throws Exception {
        UUID course = TestTenant.course("Software Engineering");
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

        when(sessionExtractionService.extract(eq("Lecture 3: Testing"), eq(sessionText), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any())).thenReturn(List.of(
                skill(new ExtractedGoal("Apply TDD.", GoalKind.EXPLICIT, "...failing test first...")),
                skill(new ExtractedGoal("Understand SE scope.", GoalKind.IMPLICIT, "...overview..."))
        ));
        when(sessionExtractionService.extract(eq("Exercise 3.2: Kata"), eq(exerciseText), eq("en"), eq("English"), eq(null), anyList(), anyInt(), any())).thenReturn(List.of(
                skill(new ExtractedGoal("Practise TDD kata.", GoalKind.EXPLICIT, "...kata..."))
        ));

        startExtraction(course);

        // Module root + one session node + one exercise node (the title keyword picks EXERCISE).
        List<HierarchyNode> nodes = hierarchyRepository.findByCourseId(course);
        assertThat(nodes).extracting(HierarchyNode::getLevel)
                .contains(HierarchyLevel.MODULE, HierarchyLevel.SESSION, HierarchyLevel.EXERCISE,
                        HierarchyLevel.COMPETENCY);

        // Each goal is attached to the node of the section its chunk came from (deterministic, by
        // offset). Labels are resolved through the already-loaded nodes: the goal's hierarchyNode is a
        // lazy proxy and the session is closed, but reading its id never triggers initialization.
        Map<Long, String> labelsByNodeId = nodes.stream()
                .collect(Collectors.toMap(HierarchyNode::getId, HierarchyNode::getLabel));
        List<PlacedGoal> goals = GoalFixtures.goals(course);
        assertThat(goals).filteredOn(g -> g.origin() != GoalOrigin.SYNTHESIZED)
                .allSatisfy(g -> assertThat(g.hierarchyNode()).isNotNull());
        assertThat(goals)
                .filteredOn(g -> g.text().equals("Practise TDD kata."))
                .singleElement()
                .satisfies(g -> assertThat(labelsByNodeId.get(g.hierarchyNode().getId()))
                        .isEqualTo("Exercise 3.2: Kata"));
        assertThat(goals)
                .filteredOn(g -> g.text().equals("Apply TDD."))
                .singleElement()
                .satisfies(g -> assertThat(labelsByNodeId.get(g.hierarchyNode().getId()))
                        .isEqualTo("Lecture 3: Testing"));
        PlacedGoal lectureGoal = goals.stream()
                .filter(g -> g.text().equals("Apply TDD."))
                .findFirst().orElseThrow();
        PlacedGoal exerciseGoal = goals.stream()
                .filter(g -> g.text().equals("Practise TDD kata."))
                .findFirst().orElseThrow();
        assertThat(lectureGoal.lectureOrder()).isLessThan(exerciseGoal.lectureOrder());
    }
}
