package de.tum.cit.hestia.materials.goal;

import static org.assertj.core.api.Assertions.assertThat;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalKind;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRelationInfo;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.core.GoalStatus;
import java.time.Instant;
import java.util.UUID;
import de.tum.cit.hestia.materials.document.Document;
import de.tum.cit.hestia.materials.hierarchy.HierarchyLevel;
import de.tum.cit.hestia.materials.hierarchy.HierarchyNode;
import de.tum.cit.hestia.materials.taxonomy.BloomLevel;
import de.tum.cit.hestia.materials.taxonomy.SoloLevel;
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
        UUID course = UUID.randomUUID();
        HierarchyNode module = node(course, null, HierarchyLevel.MODULE, "Software Engineering", 1L);
        HierarchyNode session = node(course, module, HierarchyLevel.SESSION, "Session 3: Testing", 2L);
        HierarchyNode exercise = node(course, session, HierarchyLevel.EXERCISE, "Exercise 3.2: TDD Kata", 3L);

        G moduleGoal = goal(course, "Understand SE scope.", GoalKind.IMPLICIT, module, 10L);
        G sessionGoal = goal(course, "Apply TDD.", GoalKind.EXPLICIT, session, 11L);
        G exerciseGoal = goal(course, "Practise the kata.", GoalKind.EXPLICIT, exercise, 12L);
        G orphanGoal = goal(course, "Something unlinked.", GoalKind.IMPLICIT, null, 13L);

        StringWriter out = new StringWriter();
        writer.write(out, goals(moduleGoal, sessionGoal, exerciseGoal, orphanGoal), List.of(), List.of());

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.get(0)).containsExactly(
                "id", "parent_ids", "tree_depth", "tier", "role", "origin", "created_by", "learning_goal",
                "short_label", "kind", "bloom", "solo", "status", "hierarchy_module", "hierarchy_session",
                "hierarchy_exercise", "sources", "quotes", "grounded");
        assertThat(rows.get(1)).containsExactly(
                u(10).toString(), "", "", "", "", "EXTRACTED", "", "Understand SE scope.", "", "IMPLICIT", "", "", "PENDING",
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
        UUID course = UUID.randomUUID();
        StringWriter out = new StringWriter();
        writer.write(out, goals(goal(course, "Verstehe Übergänge.", GoalKind.EXPLICIT, null, 1L)),
                List.of(), List.of());

        assertThat(out.toString()).startsWith(LearningGoalCsvWriter.BOM + "\"id\"");
    }

    @Test
    void ordersRowsByTopicTreeDepthFirstThenGoalsOutsideTheTree() throws IOException, CsvException {
        UUID course = UUID.randomUUID();
        G loose = goal(course, "Loose goal.", GoalKind.EXPLICIT, null, 1L);
        G topicB = topic(course, "Topic B", 2L, 2);
        G topicA = topic(course, "Topic A", 3L, 1);
        G skill = goal(course, "Write tests.", GoalKind.EXPLICIT, null, 4L);
        skill.setRole(GoalRole.SKILL);
        G knowledge = goal(course, "Test pyramid.", GoalKind.EXPLICIT, null, 5L);
        knowledge.setRole(GoalRole.KNOWLEDGE);
        G underB = goal(course, "Under B.", GoalKind.EXPLICIT, null, 6L);

        StringWriter out = new StringWriter();
        writer.write(out, goals(loose, topicB, topicA, skill, knowledge, underB), List.of(), List.of(
                edge(knowledge, skill), edge(skill, topicA), edge(underB, topicB)));

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.stream().skip(1).map(r -> r[0] + "|" + r[1] + "|" + r[2] + "|" + r[3]).toList())
                .containsExactly(u(3) + "||0|TOPIC", u(4) + "|" + u(3) + "|1|SUB_SKILL",
                        u(5) + "|" + u(4) + "|2|KNOWLEDGE", u(2) + "||0|TOPIC", u(6) + "|" + u(2) + "|1|KNOWLEDGE",
                        u(1) + "|||");
        assertThat(cell(rows.get(1), "origin")).isEqualTo("TERMINAL");
        assertThat(cell(rows.get(2), "role")).isEqualTo("SKILL");
        assertThat(cell(rows.get(3), "role")).isEqualTo("KNOWLEDGE");
    }

    @Test
    void listsEveryParentButWritesAGoalWithTwoParentsOnce() throws IOException, CsvException {
        UUID course = UUID.randomUUID();
        G topicA = topic(course, "Topic A", 1L, 1);
        G topicB = topic(course, "Topic B", 2L, 2);
        G shared = goal(course, "Shared.", GoalKind.EXPLICIT, null, 3L);

        StringWriter out = new StringWriter();
        writer.write(out, goals(topicA, topicB, shared), List.of(),
                List.of(edge(shared, topicA), edge(shared, topicB), edge(shared, topicA)));

        List<String[]> rows = readAll(out.toString());
        assertThat(rows).hasSize(4);
        assertThat(rows.get(2)).startsWith(u(3).toString(), u(1) + "; " + u(2), "1");
        assertThat(cell(rows.get(2), "tier")).isEqualTo("KNOWLEDGE");
    }

    @Test
    void leavesOutLegacyGapGoalsAndLegacyEdges() throws IOException, CsvException {
        UUID course = UUID.randomUUID();
        G a = goal(course, "A", GoalKind.EXPLICIT, null, 1L);
        G b = goal(course, "B", GoalKind.EXPLICIT, null, 2L);
        G gap = goal(course, "Gap", GoalKind.IMPLICIT, null, 3L);
        gap.setOrigin(GoalOrigin.GAP);

        StringWriter out = new StringWriter();
        writer.write(out, goals(a, b, gap), List.of(), List.of(
                edge(a, b, RelationshipType.PREREQUISITE_OF),
                edge(b, gap)));

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.stream().skip(1).map(r -> r[0]).toList()).containsExactly(u(1).toString(), u(2).toString());
        assertThat(cell(rows.get(1), "parent_ids")).isEmpty();
        assertThat(cell(rows.get(2), "parent_ids")).isEmpty();
    }

    @Test
    void writesStatusProvenanceAndShortLabel() throws IOException, CsvException {
        UUID course = UUID.randomUUID();
        G g = goal(course, "Apply TDD.", GoalKind.EXPLICIT, null, 70L);
        g.setStatus(GoalStatus.APPROVED);
        g.setCreationProvenance(GoalCreationProvenance.USER_CREATED);
        g.setShortLabel("TDD");

        StringWriter out = new StringWriter();
        writer.write(out, goals(g), List.of(), List.of());

        List<String[]> rows = readAll(out.toString());
        assertThat(cell(rows.get(1), "created_by")).isEqualTo("USER_CREATED");
        assertThat(cell(rows.get(1), "short_label")).isEqualTo("TDD");
        assertThat(cell(rows.get(1), "status")).isEqualTo("APPROVED");
    }

    @Test
    void writesSourcesWithPagesAndWhetherAllAreGrounded() throws IOException, CsvException {
        UUID course = UUID.randomUUID();
        G both = goal(course, "Apply TDD.", GoalKind.EXPLICIT, null, 20L);
        G partly = goal(course, "Refactor.", GoalKind.EXPLICIT, null, 21L);
        G none = goal(course, "No source.", GoalKind.EXPLICIT, null, 22L);
        Document lecture = document(course, "lecture.pdf", 100L);
        Document exercise = document(course, "exercise.pdf", 101L);

        StringWriter out = new StringWriter();
        writer.write(out, goals(both, partly, none), List.of(
                new GoalSource(both.id(), lecture, "snippet a", 12, true),
                new GoalSource(both.id(), exercise, "snippet b", null, true),
                new GoalSource(partly.id(), lecture, "snippet c", 3, true),
                new GoalSource(partly.id(), exercise, "snippet d", 4, false)), List.of());

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
        UUID course = UUID.randomUUID();
        G full = goal(course, "Apply TDD.", GoalKind.EXPLICIT, null, 40L);
        full.setBloomLevel(BloomLevel.APPLY);
        full.setSoloLevel(SoloLevel.RELATIONAL);
        G none = goal(course, "Unclassified.", GoalKind.IMPLICIT, null, 41L);

        StringWriter out = new StringWriter();
        writer.write(out, goals(full, none), List.of(), List.of());

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.get(1)).contains("APPLY", "RELATIONAL");
        assertThat(cell(rows.get(1), "bloom")).isEqualTo("APPLY");
        assertThat(cell(rows.get(1), "solo")).isEqualTo("RELATIONAL");
        assertThat(cell(rows.get(2), "bloom")).isEmpty();
        assertThat(cell(rows.get(2), "solo")).isEmpty();
    }

    @Test
    void namesTheTierAsTheClientDrawsIt() throws IOException, CsvException {
        UUID course = UUID.randomUUID();
        G topic = topic(course, "Testing", 1L, 1);
        G skill = skillGoal(course, "Test software.", 2L);
        G subSkill = skillGoal(course, "Write unit tests.", 3L);
        G knowledge = goal(course, "Assertions.", GoalKind.EXPLICIT, null, 4L);
        knowledge.setRole(GoalRole.KNOWLEDGE);
        G belowKnowledge = goal(course, "Too deep.", GoalKind.EXPLICIT, null, 5L);
        G directSubSkill = skillGoal(course, "Refactor.", 6L);
        G legacyApply = goal(course, "Apply mocks.", GoalKind.EXPLICIT, null, 7L);
        legacyApply.setBloomLevel(BloomLevel.APPLY);
        G handAddedSkill = skillGoal(course, "Hand-added.", 8L);
        handAddedSkill.setCreationProvenance(GoalCreationProvenance.USER_CREATED);

        StringWriter out = new StringWriter();
        writer.write(out, goals(topic, skill, subSkill, knowledge, belowKnowledge, directSubSkill, legacyApply,
                handAddedSkill), List.of(), List.of(
                edge(skill, topic), edge(subSkill, skill), edge(knowledge, subSkill), edge(belowKnowledge, knowledge),
                edge(directSubSkill, topic), edge(legacyApply, topic), edge(handAddedSkill, topic)));

        List<String[]> rows = readAll(out.toString());
        assertThat(rows.stream().skip(1).map(r -> r[0] + "|" + cell(r, "tier")).toList()).containsExactly(
                u(1) + "|TOPIC", u(2) + "|SKILL", u(3) + "|SUB_SKILL", u(4) + "|KNOWLEDGE", u(6) + "|SUB_SKILL",
                u(7) + "|SUB_SKILL", u(8) + "|SKILL", u(5) + "|");
    }

    @Test
    void escapesCommasAndQuotesAndNewlinesInGoalText() throws IOException, CsvException {
        UUID course = UUID.randomUUID();
        G g = goal(course,
                "Apply \"TDD\", red-green-refactor,\nand keep tests fast.",
                GoalKind.EXPLICIT, null, 30L);

        StringWriter out = new StringWriter();
        writer.write(out, goals(g), List.of(), List.of());

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

    private static UUID u(long n) {
        return new UUID(0, n);
    }

    private static HierarchyNode node(UUID course, HierarchyNode parent, HierarchyLevel level, String label, Long id) {
        HierarchyNode n = new HierarchyNode(course, parent, level, label);
        setField(n, "id", id);
        return n;
    }

    /** A goal under construction; the setters are those of the learning goal the writer once took. */
    private static final class G {
        private final UUID id;
        private final String text;
        private final GoalKind kind;
        private GoalRole role;
        private GoalOrigin origin = GoalOrigin.EXTRACTED;
        private GoalStatus status = GoalStatus.PENDING;
        private GoalCreationProvenance provenance;
        private String shortLabel;
        private BloomLevel bloom;
        private SoloLevel solo;
        private HierarchyNode node;
        private Integer lectureOrder;

        private G(UUID id, String text, GoalKind kind) {
            this.id = id;
            this.text = text;
            this.kind = kind;
        }

        UUID id() {
            return id;
        }

        void setRole(GoalRole role) {
            this.role = role;
        }

        void setOrigin(GoalOrigin origin) {
            this.origin = origin;
        }

        void setStatus(GoalStatus status) {
            this.status = status;
        }

        void setCreationProvenance(GoalCreationProvenance provenance) {
            this.provenance = provenance;
        }

        void setShortLabel(String shortLabel) {
            this.shortLabel = shortLabel;
        }

        void setBloomLevel(BloomLevel bloom) {
            this.bloom = bloom;
        }

        void setSoloLevel(SoloLevel solo) {
            this.solo = solo;
        }

        void setLectureOrder(Integer lectureOrder) {
            this.lectureOrder = lectureOrder;
        }

        PlacedGoal build(UUID course) {
            GoalInfo info = new GoalInfo(id, course, 1, text, text, shortLabel, kind, role,
                    bloom == null ? null : bloom.name(), solo == null ? null : solo.name(), status, origin,
                    provenance == null ? null : provenance.name(), DomainModule.MATERIALS, null, Instant.now(), null);
            return new PlacedGoal(info, node == null && lectureOrder == null
                    ? null : new GoalPlacement(id, node, null, lectureOrder));
        }
    }

    private static final UUID COURSE = UUID.randomUUID();

    private static List<PlacedGoal> goals(G... goals) {
        return java.util.Arrays.stream(goals).map(g -> g.build(COURSE)).toList();
    }

    private static G goal(UUID course, String text, GoalKind kind, HierarchyNode node, Long id) {
        G g = new G(u(id), text, kind);
        g.node = node;
        return g;
    }

    private static G topic(UUID course, String text, Long id, int lectureOrder) {
        G g = goal(course, text, GoalKind.IMPLICIT, null, id);
        g.setOrigin(GoalOrigin.TERMINAL);
        g.setLectureOrder(lectureOrder);
        return g;
    }

    private static G skillGoal(UUID course, String text, Long id) {
        G g = goal(course, text, GoalKind.EXPLICIT, null, id);
        g.setRole(GoalRole.SKILL);
        return g;
    }

    private static GoalRelationInfo edge(G child, G parent) {
        return edge(child, parent, RelationshipType.CONTRIBUTES_TO);
    }

    private static GoalRelationInfo edge(G child, G parent, RelationshipType type) {
        return new GoalRelationInfo(UUID.randomUUID(), child.id(), parent.id(), type.name(), 1.0,
                RelationshipOrigin.HIERARCHY.name(), Instant.now());
    }

    private static Document document(UUID course, String filename, Long id) {
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
