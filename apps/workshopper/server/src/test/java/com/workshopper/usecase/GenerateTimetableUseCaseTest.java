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
}
