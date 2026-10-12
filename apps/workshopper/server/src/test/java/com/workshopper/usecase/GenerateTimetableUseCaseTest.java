package com.workshopper.usecase;

import com.workshopper.dto.ActivityBlockDto;
import com.workshopper.dto.ActivitySectionDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateTimetableUseCaseTest {

    @Test
    void parseMaterialsSplitsLinesAndDropsBlanks() {
        assertThat(GenerateTimetableUseCase.parseMaterials("Projector / Screen\n\n  Whiteboard / Blackboard \n"))
                .containsExactly("Projector / Screen", "Whiteboard / Blackboard");
        assertThat(GenerateTimetableUseCase.parseMaterials("  ")).isEmpty();
        assertThat(GenerateTimetableUseCase.parseMaterials(null)).isEmpty();
    }

    @Test
    void restrictToSelectionsKeepsOnlyUserChoices() {
        var section = new ActivitySectionDto("Participants Practice", 10, List.of("10 min - Poll"),
                List.of("quiz / polls", "Think-Pair-Share"), List.of("Sticky Notes & Markers", "Projector / Screen"));
        var block = new ActivityBlockDto("b1", "LEARNING_CYCLE", "Intro", "LG1", "obj", "desc",
                List.of("Group Discussion", "Quiz / Polls"), List.of("Laptops / Computers"), List.of(section), 10);

        var result = GenerateTimetableUseCase.restrictToSelections(block,
                List.of("Quiz / Polls"), List.of("Projector / Screen"));

        assertThat(result.methods()).containsExactly("Quiz / Polls");
        assertThat(result.materials()).isEmpty();
        assertThat(result.sections().get(0).methods()).containsExactly("Quiz / Polls");
        assertThat(result.sections().get(0).materials()).containsExactly("Projector / Screen");
        assertThat(result.sections().get(0).steps()).containsExactly("10 min - Poll");
    }

    @Test
    void restrictToSelectionsLeavesActivitiesOpenWhenNoneSelected() {
        var block = new ActivityBlockDto("b1", "LEARNING_CYCLE", "Intro", "LG1", "obj", "desc",
                List.of("Group Discussion"), List.of("Laptops / Computers"), List.of(), 10);

        var result = GenerateTimetableUseCase.restrictToSelections(block, null, List.of());

        assertThat(result.methods()).containsExactly("Group Discussion");
        assertThat(result.materials()).isEmpty();
    }

    @Test
    void normalizeEvaluateStepsMergesSplitQuestionsIntoOnePerBox() {
        var section = new ActivitySectionDto("Check", 10, List.of(
                "5 min - Goal 1 & 2 - Worked Problem: Solve a problem on decision regions.",
                "5 min - Goal 3 - Worked Problem: Solve a multiclass problem using the Iris dataset."), List.of(), List.of());
        var block = new ActivityBlockDto("b1", "EVALUATE", "Check Understanding", "", "", "",
                List.of("Worked Problem"), List.of(), List.of(section), 10);
        var slots = List.of(new GenerateTimetableUseCase.EvaluateSlot("Worked Problem", List.of(1, 2, 3), 10));

        var result = GenerateTimetableUseCase.normalizeEvaluateSteps(block, slots);

        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().get(0).steps()).containsExactly(
                "10 min - Goal 1, 2 & 3 - Worked Problem: Solve a problem on decision regions. "
                        + "Solve a multiclass problem using the Iris dataset.");
        assertThat(result.duration()).isEqualTo(10);
    }

    @Test
    void normalizeEvaluateStepsKeepsOneStepPerBoxInMappingOrder() {
        var section = new ActivitySectionDto("Check", 10, List.of(
                "4 min - Goal 3 & 4 - Debate: Argue which model fits better.",
                "6 min - Goal 1 - Quiz / Polls: Name the two parameters of a linear classifier."), List.of(), List.of());
        var block = new ActivityBlockDto("b1", "EVALUATE", "Check Understanding", "", "", "",
                List.of(), List.of(), List.of(section), 10);
        var slots = List.of(
                new GenerateTimetableUseCase.EvaluateSlot("Quiz / Polls", List.of(1, 2), 5),
                new GenerateTimetableUseCase.EvaluateSlot("Debate", List.of(3, 4), 5));

        var result = GenerateTimetableUseCase.normalizeEvaluateSteps(block, slots);

        assertThat(result.sections().get(0).steps()).containsExactly(
                "5 min - Goal 1 & 2 - Quiz / Polls: Name the two parameters of a linear classifier.",
                "5 min - Goal 3 & 4 - Debate: Argue which model fits better.");
        assertThat(result.methods()).containsExactly("Quiz / Polls", "Debate");
    }
}
