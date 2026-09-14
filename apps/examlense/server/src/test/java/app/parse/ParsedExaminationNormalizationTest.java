package app.parse;

import app.taskblock.AnswerOption;
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
class ParsedExaminationNormalizationTest {

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
        ParsedExaminationPersister.fillMissingSections(Map.of(), tasks);

        assertThat(tasks).extracting(t -> t.get("section"))
            .containsExactly("Part A", "Part A", "Part A", "Part B", "Part B");
    }

    @Test
    void fillMissingSectionsSynthesizesForTaskBlockBlocksBeforeAnySection() {
        List<Map<String, Object>> tasks = new ArrayList<>(List.of(
            task(null), task("Part A")
        ));
        ParsedExaminationPersister.fillMissingSections(Map.of("title", "Algebra Final"), tasks);

        assertThat(tasks.get(0)).containsEntry("section", "Algebra Final");
        assertThat(tasks.get(1)).containsEntry("section", "Part A");
    }

    @Test
    void syntheticSectionPrefersTitleThenCourseThenLanguageDefault() {
        assertThat(ParsedExaminationPersister.pickSyntheticSection(Map.of("title", "T", "course", "C"))).isEqualTo("T");
        assertThat(ParsedExaminationPersister.pickSyntheticSection(Map.of("course", "C"))).isEqualTo("C");
        assertThat(ParsedExaminationPersister.pickSyntheticSection(Map.of("detected_language", "de"))).isEqualTo("Aufgaben");
        assertThat(ParsedExaminationPersister.pickSyntheticSection(Map.of("detected_language", "en"))).isEqualTo("Tasks");
        assertThat(ParsedExaminationPersister.pickSyntheticSection(Map.of())).isEqualTo("Tasks");
    }

    @Test
    void resolvePositionMapsAfterTaskBlockIndexOntoPositions() {
        List<Integer> positions = List.of(1, 2, 3);
        // After task 0 → position of the next task.
        assertThat(ParsedExaminationPersister.resolvePosition(positions, 0)).isEqualTo(2);
        // After the last task → one past its position.
        assertThat(ParsedExaminationPersister.resolvePosition(positions, 2)).isEqualTo(4);
        // Out of range / missing / negative → top of section.
        assertThat(ParsedExaminationPersister.resolvePosition(positions, 7)).isZero();
        assertThat(ParsedExaminationPersister.resolvePosition(positions, null)).isZero();
        assertThat(ParsedExaminationPersister.resolvePosition(positions, -1)).isZero();
        assertThat(ParsedExaminationPersister.resolvePosition(List.of(), 0)).isZero();
        assertThat(ParsedExaminationPersister.resolvePosition(null, 0)).isZero();
    }

    @Test
    void normalizeTaskBlockTypeKeepsValidValues() {
        assertThat(ParsedExaminationPersister.normalizeTaskBlockType("text", null)).isEqualTo("text");
        assertThat(ParsedExaminationPersister.normalizeTaskBlockType("single_choice", null)).isEqualTo("single_choice");
        assertThat(ParsedExaminationPersister.normalizeTaskBlockType("multiple_choice", null)).isEqualTo("multiple_choice");
    }

    @Test
    void normalizeTaskBlockTypeInfersChoiceTypesFromOptions() {
        List<AnswerOption> oneCorrect = List.of(
            new AnswerOption("1", "a", true), new AnswerOption("2", "b", false));
        List<AnswerOption> twoCorrect = List.of(
            new AnswerOption("1", "a", true), new AnswerOption("2", "b", true));

        assertThat(ParsedExaminationPersister.normalizeTaskBlockType(null, oneCorrect)).isEqualTo("single_choice");
        assertThat(ParsedExaminationPersister.normalizeTaskBlockType("garbled", twoCorrect)).isEqualTo("multiple_choice");
    }

    @Test
    void normalizeTaskBlockTypeFallsBackToText() {
        assertThat(ParsedExaminationPersister.normalizeTaskBlockType(null, null)).isEqualTo("text");
        assertThat(ParsedExaminationPersister.normalizeTaskBlockType("essay", List.of())).isEqualTo("text");
        assertThat(ParsedExaminationPersister.normalizeTaskBlockType(42, null)).isEqualTo("text");
    }
}
