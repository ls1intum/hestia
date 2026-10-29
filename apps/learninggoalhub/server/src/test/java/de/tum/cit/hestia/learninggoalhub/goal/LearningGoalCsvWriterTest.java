package de.tum.cit.hestia.learninggoalhub.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.taxonomy.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.taxonomy.SoloLevel;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class LearningGoalCsvWriterTest {

    private final LearningGoalCsvWriter writer = new LearningGoalCsvWriter();

    @Test
    void writesHeaderAndDerivesHierarchyLevels() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        HierarchyNode module = node(course, null, HierarchyLevel.MODULE, "Software Engineering", 1L);
        HierarchyNode session = node(course, module, HierarchyLevel.SESSION, "Session 3: Testing", 2L);
        HierarchyNode exercise = node(course, session, HierarchyLevel.EXERCISE, "Exercise 3.2: TDD Kata", 3L);

        LearningGoal moduleGoal = goal(course, "Understand SE scope.", GoalKind.IMPLICIT, module, 10L);
        LearningGoal sessionGoal = goal(course, "Apply TDD.", GoalKind.EXPLICIT, session, 11L);
        LearningGoal exerciseGoal = goal(course, "Practise the kata.", GoalKind.EXPLICIT, exercise, 12L);
        LearningGoal orphanGoal = goal(course, "Something unlinked.", GoalKind.IMPLICIT, null, 13L);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(moduleGoal, sessionGoal, exerciseGoal, orphanGoal), List.of(), List.of());

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.get(0)).containsExactly(
                "id", "parent_ids", "tree_depth", "tier", "role", "origin", "created_by", "learning_goal",
                "short_label", "kind", "bloom", "solo", "status", "hierarchy_module", "hierarchy_session",
                "hierarchy_exercise", "sources", "quotes", "grounded");
        assertThat(rows.get(1)).containsExactly(
                "10", "", "", "", "", "EXTRACTED", "", "Understand SE scope.", "", "IMPLICIT", "", "", "PENDING",
                "Software Engineering", "", "", "", "", "");
        assertThat(rows.get(2)).containsSubsequence("Software Engineering", "Session 3: Testing", "");
        assertThat(rows.get(3)).containsSubsequence(
                "Software Engineering", "Session 3: Testing", "Exercise 3.2: TDD Kata");
        assertThat(cell(rows.get(4), "hierarchy_module")).isEmpty();
        assertThat(cell(rows.get(4), "hierarchy_session")).isEmpty();
        assertThat(cell(rows.get(4), "hierarchy_exercise")).isEmpty();
    }

    @Test
    void startsWithAByteOrderMarkSoExcelReadsUtf8() throws IOException {
        Course course = new Course("Software Engineering");
        StringWriter out = new StringWriter();
        writer.write(out, List.of(goal(course, "Verstehe Übergänge.", GoalKind.EXPLICIT, null, 1L)),
                List.of(), List.of());

        assertThat(out.toString()).startsWith(LearningGoalCsvWriter.BOM + "\"id\"");
    }

    @Test
    void ordersRowsByTopicTreeDepthFirstThenGoalsOutsideTheTree() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal loose = goal(course, "Loose goal.", GoalKind.EXPLICIT, null, 1L);
        LearningGoal topicB = topic(course, "Topic B", 2L, 2);
        LearningGoal topicA = topic(course, "Topic A", 3L, 1);
        LearningGoal skill = goal(course, "Write tests.", GoalKind.EXPLICIT, null, 4L);
        skill.setRole(GoalRole.SKILL);
        LearningGoal knowledge = goal(course, "Test pyramid.", GoalKind.EXPLICIT, null, 5L);
        knowledge.setRole(GoalRole.KNOWLEDGE);
        LearningGoal underB = goal(course, "Under B.", GoalKind.EXPLICIT, null, 6L);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(loose, topicB, topicA, skill, knowledge, underB), List.of(), List.of(
                edge(knowledge, skill), edge(skill, topicA), edge(underB, topicB)));

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.stream().skip(1).map(r -> r[0] + "|" + r[1] + "|" + r[2] + "|" + r[3]).toList())
                .containsExactly("3||0|TOPIC", "4|3|1|SUB_SKILL", "5|4|2|KNOWLEDGE", "2||0|TOPIC", "6|2|1|KNOWLEDGE",
                        "1|||");
        assertThat(cell(rows.get(1), "origin")).isEqualTo("TERMINAL");
        assertThat(cell(rows.get(2), "role")).isEqualTo("SKILL");
        assertThat(cell(rows.get(3), "role")).isEqualTo("KNOWLEDGE");
    }

    @Test
    void listsEveryParentButWritesAGoalWithTwoParentsOnce() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal topicA = topic(course, "Topic A", 1L, 1);
        LearningGoal topicB = topic(course, "Topic B", 2L, 2);
        LearningGoal shared = goal(course, "Shared.", GoalKind.EXPLICIT, null, 3L);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(topicA, topicB, shared), List.of(),
                List.of(edge(shared, topicA), edge(shared, topicB), edge(shared, topicA)));

        List<String[]> rows = readAll(out.toString());
        assertThat(rows).hasSize(4);
        assertThat(rows.get(2)).startsWith("3", "1; 2", "1");
        assertThat(cell(rows.get(2), "tier")).isEqualTo("KNOWLEDGE");
    }

    @Test
    void leavesOutLegacyGapGoalsAndLegacyEdges() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal a = goal(course, "A", GoalKind.EXPLICIT, null, 1L);
        LearningGoal b = goal(course, "B", GoalKind.EXPLICIT, null, 2L);
        LearningGoal gap = goal(course, "Gap", GoalKind.IMPLICIT, null, 3L);
        gap.setOrigin(GoalOrigin.GAP);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(a, b, gap), List.of(), List.of(
                new GoalRelationship(a, b, RelationshipType.PREREQUISITE_OF, 0.9, RelationshipOrigin.LLM),
                edge(b, gap)));

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.stream().skip(1).map(r -> r[0]).toList()).containsExactly("1", "2");
        assertThat(cell(rows.get(1), "parent_ids")).isEmpty();
        assertThat(cell(rows.get(2), "parent_ids")).isEmpty();
    }

    @Test
    void writesStatusProvenanceAndShortLabel() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal g = goal(course, "Apply TDD.", GoalKind.EXPLICIT, null, 70L);
        g.setStatus(GoalStatus.APPROVED);
        g.setCreationProvenance(GoalCreationProvenance.USER_CREATED);
        g.setShortLabel("TDD");

        StringWriter out = new StringWriter();
        writer.write(out, List.of(g), List.of(), List.of());

        List<String[]> rows = readAll(out.toString());
        assertThat(cell(rows.get(1), "created_by")).isEqualTo("USER_CREATED");
        assertThat(cell(rows.get(1), "short_label")).isEqualTo("TDD");
        assertThat(cell(rows.get(1), "status")).isEqualTo("APPROVED");
    }

    @Test
    void writesSourcesWithPagesAndWhetherAllAreGrounded() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal both = goal(course, "Apply TDD.", GoalKind.EXPLICIT, null, 20L);
        LearningGoal partly = goal(course, "Refactor.", GoalKind.EXPLICIT, null, 21L);
        LearningGoal none = goal(course, "No source.", GoalKind.EXPLICIT, null, 22L);
        Document lecture = document(course, "lecture.pdf", 100L);
        Document exercise = document(course, "exercise.pdf", 101L);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(both, partly, none), List.of(
                new GoalSource(both, lecture, "snippet a", 12, true),
                new GoalSource(both, exercise, "snippet b", null, true),
                new GoalSource(partly, lecture, "snippet c", 3, true),
                new GoalSource(partly, exercise, "snippet d", 4, false)), List.of());

        List<String[]> rows = readAll(out.toString());
        assertThat(cell(rows.get(1), "sources")).isEqualTo("lecture.pdf p.12; exercise.pdf");
        assertThat(cell(rows.get(1), "quotes")).isEqualTo("snippet a\n---\nsnippet b");
        assertThat(cell(rows.get(1), "grounded")).isEqualTo("true");
        assertThat(cell(rows.get(2), "grounded")).isEqualTo("false");
        assertThat(cell(rows.get(3), "sources")).isEmpty();
        assertThat(cell(rows.get(3), "grounded")).isEmpty();
    }

    @Test
    void writesBloomAndSoloInSeparateColumns() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal full = goal(course, "Apply TDD.", GoalKind.EXPLICIT, null, 40L);
        full.setBloomLevel(BloomLevel.APPLY);
        full.setSoloLevel(SoloLevel.RELATIONAL);
        LearningGoal none = goal(course, "Unclassified.", GoalKind.IMPLICIT, null, 41L);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(full, none), List.of(), List.of());

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.get(1)).contains("APPLY", "RELATIONAL");
        assertThat(cell(rows.get(1), "bloom")).isEqualTo("APPLY");
        assertThat(cell(rows.get(1), "solo")).isEqualTo("RELATIONAL");
        assertThat(cell(rows.get(2), "bloom")).isEmpty();
        assertThat(cell(rows.get(2), "solo")).isEmpty();
    }

    @Test
    void namesTheTierAsTheClientDrawsIt() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal topic = topic(course, "Testing", 1L, 1);
        LearningGoal skill = skillGoal(course, "Test software.", 2L);
        LearningGoal subSkill = skillGoal(course, "Write unit tests.", 3L);
        LearningGoal knowledge = goal(course, "Assertions.", GoalKind.EXPLICIT, null, 4L);
        knowledge.setRole(GoalRole.KNOWLEDGE);
        LearningGoal belowKnowledge = goal(course, "Too deep.", GoalKind.EXPLICIT, null, 5L);
        LearningGoal directSubSkill = skillGoal(course, "Refactor.", 6L);
        LearningGoal legacyApply = goal(course, "Apply mocks.", GoalKind.EXPLICIT, null, 7L);
        legacyApply.setBloomLevel(BloomLevel.APPLY);
        LearningGoal handAddedSkill = skillGoal(course, "Hand-added.", 8L);
        handAddedSkill.setCreationProvenance(GoalCreationProvenance.USER_CREATED);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(topic, skill, subSkill, knowledge, belowKnowledge, directSubSkill, legacyApply,
                handAddedSkill), List.of(), List.of(
                edge(skill, topic), edge(subSkill, skill), edge(knowledge, subSkill), edge(belowKnowledge, knowledge),
                edge(directSubSkill, topic), edge(legacyApply, topic), edge(handAddedSkill, topic)));

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.stream().skip(1).map(r -> r[0] + "|" + cell(r, "tier")).toList()).containsExactly(
                "1|TOPIC", "2|SKILL", "3|SUB_SKILL", "4|KNOWLEDGE", "6|SUB_SKILL", "7|SUB_SKILL", "8|SKILL", "5|");
    }

    @Test
    void escapesCommasAndQuotesAndNewlinesInGoalText() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal g = goal(course,
                "Apply \"TDD\", red-green-refactor,\nand keep tests fast.",
                GoalKind.EXPLICIT, null, 30L);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(g), List.of(), List.of());

        List<String[]> rows = readAll(out.toString());
        assertThat(cell(rows.get(1), "learning_goal"))
                .isEqualTo("Apply \"TDD\", red-green-refactor,\nand keep tests fast.");
    }

    private static String cell(String[] row, String column) {
        return row[List.of(LearningGoalCsvWriter.HEADER).indexOf(column)];
    }

    private static List<String[]> readAll(String csv) throws IOException, CsvException {
        assertThat(csv.charAt(0)).isEqualTo(LearningGoalCsvWriter.BOM);
        try (CSVReader r = new CSVReader(new StringReader(csv.substring(1)))) {
            return r.readAll();
        }
    }

    private static HierarchyNode node(Course course, HierarchyNode parent, HierarchyLevel level, String label, Long id) {
        HierarchyNode n = new HierarchyNode(course, parent, level, label);
        setField(n, "id", id);
        return n;
    }

    private static LearningGoal goal(Course course, String text, GoalKind kind, HierarchyNode node, Long id) {
        LearningGoal g = new LearningGoal(course, text, kind);
        if (node != null) {
            g.setHierarchyNode(node);
        }
        setField(g, "id", id);
        return g;
    }

    private static LearningGoal topic(Course course, String text, Long id, int lectureOrder) {
        LearningGoal g = goal(course, text, GoalKind.IMPLICIT, null, id);
        g.setOrigin(GoalOrigin.TERMINAL);
        g.setLectureOrder(lectureOrder);
        return g;
    }

    private static LearningGoal skillGoal(Course course, String text, Long id) {
        LearningGoal g = goal(course, text, GoalKind.EXPLICIT, null, id);
        g.setRole(GoalRole.SKILL);
        return g;
    }

    private static GoalRelationship edge(LearningGoal child, LearningGoal parent) {
        return new GoalRelationship(child, parent, RelationshipType.CONTRIBUTES_TO, 1.0,
                RelationshipOrigin.HIERARCHY);
    }

    private static Document document(Course course, String filename, Long id) {
        Document d = new Document(course, filename, "application/pdf", "raw");
        setField(d, "id", id);
        return d;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void prefixesCellsThatASpreadsheetWouldRunAsFormulas() {
        assertThat(LearningGoalCsvWriter.neutralizeFormulas(
                new String[]{"=HYPERLINK(\"x\")", "+1", "-2", "@SUM(A1)", "\tTab", "Apply TDD.", "", null}))
                .containsExactly("'=HYPERLINK(\"x\")", "'+1", "'-2", "'@SUM(A1)", "'\tTab", "Apply TDD.", "", null);
    }
}
