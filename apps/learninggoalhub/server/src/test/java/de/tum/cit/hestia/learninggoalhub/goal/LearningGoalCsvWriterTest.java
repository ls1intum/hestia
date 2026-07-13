package de.tum.cit.hestia.learninggoalhub.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.learninggoalhub.hierarchy.HierarchyNode;
import de.tum.cit.hestia.learninggoalhub.relationships.GoalRelationship;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipOrigin;
import de.tum.cit.hestia.learninggoalhub.relationships.RelationshipType;
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
                "hierarchy_module", "hierarchy_session", "hierarchy_exercise",
                "learning_goal", "kind", "sources", "taxonomy", "relationships", "status");
        assertThat(rows.get(1)).containsExactly(
                "Software Engineering", "", "", "Understand SE scope.", "IMPLICIT", "", "", "", "PENDING");
        assertThat(rows.get(2)).containsExactly(
                "Software Engineering", "Session 3: Testing", "", "Apply TDD.", "EXPLICIT", "", "", "", "PENDING");
        assertThat(rows.get(3)).containsExactly(
                "Software Engineering", "Session 3: Testing", "Exercise 3.2: TDD Kata",
                "Practise the kata.", "EXPLICIT", "", "", "", "PENDING");
        assertThat(rows.get(4)).containsExactly(
                "", "", "", "Something unlinked.", "IMPLICIT", "", "", "", "PENDING");
    }

    @Test
    void writesApprovedStatusInStatusColumn() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal approved = goal(course, "Apply TDD.", GoalKind.EXPLICIT, null, 70L);
        approved.setStatus(GoalStatus.APPROVED);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(approved), List.of(), List.of());

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.get(1)[8]).isEqualTo("APPROVED");
    }

    @Test
    void joinsSourceFilenamesWithSemicolon() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal g = goal(course, "Apply TDD.", GoalKind.EXPLICIT, null, 20L);
        Document lecture = document(course, "lecture.pdf", 100L);
        Document exercise = document(course, "exercise.pdf", 101L);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(g), List.of(
                source(g, lecture, "snippet a"),
                source(g, exercise, "snippet b")), List.of());

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.get(1)[5]).isEqualTo("lecture.pdf; exercise.pdf");
    }

    @Test
    void writesTaxonomyColumnAsKeyValuePairs() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal full = goal(course, "Apply TDD.", GoalKind.EXPLICIT, null, 40L);
        full.setBloomLevel(BloomLevel.APPLY);
        full.setSoloLevel(SoloLevel.RELATIONAL);
        LearningGoal bloomOnly = goal(course, "Recall the steps.", GoalKind.IMPLICIT, null, 41L);
        bloomOnly.setBloomLevel(BloomLevel.REMEMBER);
        LearningGoal soloOnly = goal(course, "Connect the ideas.", GoalKind.IMPLICIT, null, 42L);
        soloOnly.setSoloLevel(SoloLevel.MULTISTRUCTURAL);
        LearningGoal none = goal(course, "Unclassified.", GoalKind.IMPLICIT, null, 43L);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(full, bloomOnly, soloOnly, none), List.of(), List.of());

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.get(1)[6]).isEqualTo("bloom=APPLY; solo=RELATIONAL");
        assertThat(rows.get(2)[6]).isEqualTo("bloom=REMEMBER");
        assertThat(rows.get(3)[6]).isEqualTo("solo=MULTISTRUCTURAL");
        assertThat(rows.get(4)[6]).isEmpty();
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
        assertThat(rows.get(1)[3])
                .isEqualTo("Apply \"TDD\", red-green-refactor,\nand keep tests fast.");
    }

    @Test
    void writesRelationshipsColumnGroupedByTypeWithTargetText() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal a = goal(course, "A", GoalKind.EXPLICIT, null, 50L);
        LearningGoal b = goal(course, "B", GoalKind.EXPLICIT, null, 51L);
        LearningGoal c = goal(course, "C", GoalKind.EXPLICIT, null, 52L);
        LearningGoal d = goal(course, "D", GoalKind.EXPLICIT, null, 53L);

        GoalRelationship aToB = new GoalRelationship(a, b, RelationshipType.PREREQUISITE_OF, 0.9,
                RelationshipOrigin.LLM);
        GoalRelationship aToC = new GoalRelationship(a, c, RelationshipType.CONTRIBUTES_TO, 1.0,
                RelationshipOrigin.HIERARCHY);
        GoalRelationship aToD = new GoalRelationship(a, d, RelationshipType.OVERLAPS_WITH, 0.85,
                RelationshipOrigin.EMBEDDING);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(a, b), List.of(), List.of(aToB, aToD, aToC));

        List<String[]> rows = readAll(out.toString());
        // Type ordering: CONTRIBUTES_TO, PREREQUISITE_OF, OVERLAPS_WITH.
        assertThat(rows.get(1)[7]).isEqualTo("CONTRIBUTES_TO→C; PREREQUISITE_OF→B; OVERLAPS_WITH→D");
        assertThat(rows.get(2)[7]).isEmpty();
    }

    @Test
    void writesRelationshipsColumnEmptyWhenGoalHasNone() throws IOException, CsvException {
        Course course = new Course("Software Engineering");
        LearningGoal a = goal(course, "A", GoalKind.EXPLICIT, null, 60L);

        StringWriter out = new StringWriter();
        writer.write(out, List.of(a), List.of(), List.of());

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.get(1)[7]).isEmpty();
    }

    private static List<String[]> readAll(String csv) throws IOException, CsvException {
        try (CSVReader r = new CSVReader(new StringReader(csv))) {
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

    private static Document document(Course course, String filename, Long id) {
        Document d = new Document(course, filename, "application/pdf", "raw");
        setField(d, "id", id);
        return d;
    }

    private static GoalSource source(LearningGoal goal, Document document, String snippet) {
        return new GoalSource(goal, document, snippet);
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
}
