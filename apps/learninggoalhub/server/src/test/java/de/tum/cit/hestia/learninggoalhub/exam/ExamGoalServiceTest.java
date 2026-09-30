package de.tum.cit.hestia.learninggoalhub.exam;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExamGoalServiceTest {

    @Test
    void tidyDropsTheClosingPeriodOfTextAndLabel() {
        assertThat(ExamGoalService.tidy(new GeneratedExamGoal(" Computing precision. ", "Compute precision.")))
                .isEqualTo(new GeneratedExamGoal("Computing precision", "Compute precision"));
    }

    @Test
    void tidyDropsALabelThatIsMissingBlankOrRepeatsTheText() {
        assertThat(ExamGoalService.tidy(new GeneratedExamGoal("Computing precision", null)).shortLabel()).isNull();
        assertThat(ExamGoalService.tidy(new GeneratedExamGoal("Computing precision", " . ")).shortLabel()).isNull();
        assertThat(ExamGoalService.tidy(new GeneratedExamGoal("Computing precision.", "computing precision"))
                .shortLabel()).isNull();
    }
}
