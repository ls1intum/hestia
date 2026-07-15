package app.parse;

import app.task.TaskOption;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defensive normalization of nondeterministic LLM output — the logic most
 * likely to regress silently.
 */
class ParsedExamNormalizationTest {

    private static Map<String, Object> task(String section) {
        Map<String, Object> t = new HashMap<>();
        if (section != null) t.put("section", section);
        return t;
    }

    @Test
    void fillMissingSectionsCarriesForwardTheLastSeenSection() {
        List<Map<String, Object>> tasks = new ArrayList<>(List.of(
            task("Part A"), task(null), task("  "), task("Part B"), task(null)
        ));
        ParsedExamPersister.fillMissingSections(Map.of(), tasks, null);

        assertThat(tasks).extracting(t -> t.get("section"))
            .containsExactly("Part A", "Part A", "Part A", "Part B", "Part B");
    }

    @Test
    void fillMissingSectionsSynthesizesForTasksBeforeAnySection() {
        List<Map<String, Object>> tasks = new ArrayList<>(List.of(
            task(null), task("Part A")
        ));
        ParsedExamPersister.fillMissingSections(Map.of("title", "Algebra Final"), tasks, null);

        assertThat(tasks.get(0)).containsEntry("section", "Algebra Final");
        assertThat(tasks.get(1)).containsEntry("section", "Part A");
    }

    @Test
    void syntheticSectionPrefersTitleThenCourseThenLanguageDefault() {
        assertThat(ParsedExamPersister.pickSyntheticSection(Map.of("title", "T", "course", "C"), null)).isEqualTo("T");
        assertThat(ParsedExamPersister.pickSyntheticSection(Map.of("course", "C"), null)).isEqualTo("C");
        assertThat(ParsedExamPersister.pickSyntheticSection(Map.of(), "de")).isEqualTo("Aufgaben");
        assertThat(ParsedExamPersister.pickSyntheticSection(Map.of("detected_language", "de"), "en")).isEqualTo("Aufgaben");
        assertThat(ParsedExamPersister.pickSyntheticSection(Map.of(), "en")).isEqualTo("Tasks");
    }

    @Test
    void resolvePositionMapsAfterTaskIndexOntoPositions() {
        List<Integer> positions = List.of(1, 2, 3);
        // After task 0 → position of the next task.
        assertThat(ParsedExamPersister.resolvePosition(positions, 0)).isEqualTo(2);
        // After the last task → one past its position.
        assertThat(ParsedExamPersister.resolvePosition(positions, 2)).isEqualTo(4);
        // Out of range / missing / negative → top of section.
        assertThat(ParsedExamPersister.resolvePosition(positions, 7)).isZero();
        assertThat(ParsedExamPersister.resolvePosition(positions, null)).isZero();
        assertThat(ParsedExamPersister.resolvePosition(positions, -1)).isZero();
        assertThat(ParsedExamPersister.resolvePosition(List.of(), 0)).isZero();
        assertThat(ParsedExamPersister.resolvePosition(null, 0)).isZero();
    }

    @Test
    void normalizeTaskTypeKeepsValidValues() {
        assertThat(ParsedExamPersister.normalizeTaskType("text", null)).isEqualTo("text");
        assertThat(ParsedExamPersister.normalizeTaskType("single_choice", null)).isEqualTo("single_choice");
        assertThat(ParsedExamPersister.normalizeTaskType("multiple_choice", null)).isEqualTo("multiple_choice");
    }

    @Test
    void normalizeTaskTypeInfersChoiceTypesFromOptions() {
        List<TaskOption> oneCorrect = List.of(
            new TaskOption("1", "a", true), new TaskOption("2", "b", false));
        List<TaskOption> twoCorrect = List.of(
            new TaskOption("1", "a", true), new TaskOption("2", "b", true));

        assertThat(ParsedExamPersister.normalizeTaskType(null, oneCorrect)).isEqualTo("single_choice");
        assertThat(ParsedExamPersister.normalizeTaskType("garbled", twoCorrect)).isEqualTo("multiple_choice");
    }

    @Test
    void normalizeTaskTypeFallsBackToText() {
        assertThat(ParsedExamPersister.normalizeTaskType(null, null)).isEqualTo("text");
        assertThat(ParsedExamPersister.normalizeTaskType("essay", List.of())).isEqualTo("text");
        assertThat(ParsedExamPersister.normalizeTaskType(42, null)).isEqualTo("text");
    }
}
