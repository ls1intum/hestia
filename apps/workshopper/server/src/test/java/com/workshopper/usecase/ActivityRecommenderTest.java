package com.workshopper.usecase;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityRecommenderTest {

    @Test
    void usesFirstBestFitWhenUserSelectedNothing() {
        assertThat(ActivityRecommender.recommend("APPLY", null, List.of())).isEqualTo("Worked Problem");
        assertThat(ActivityRecommender.recommend(null, "EXTENDED_ABSTRACT", null)).isEqualTo("Design Sprint / Prototype Challenge");
    }

    @Test
    void picksSelectedActivityFromEitherTaxonomyAtTheGoalsLevel() {
        // Analyze (Bloom) has no selected activity, Relational (SOLO) has Think-Pair-Share
        assertThat(ActivityRecommender.recommend("Analyze", "Relational", List.of("Quiz / Polls", "Think-Pair-Share")))
                .isEqualTo("Think-Pair-Share");
    }

    @Test
    void movesDownLevelsUntilASelectedActivityFits() {
        // Create -> Evaluate -> Analyze -> Apply: Hands-on Practice is the first selected match
        assertThat(ActivityRecommender.recommend("CREATE", null, List.of("Quiz / Polls", "Hands-on Practice")))
                .isEqualTo("Hands-on Practice");
        assertThat(ActivityRecommender.recommend("Evaluate", null, List.of("quiz / polls"))).isEqualTo("quiz / polls");
    }

    @Test
    void returnsNullWhenNothingFitsOrLevelUnknown() {
        assertThat(ActivityRecommender.recommend("Remember", null, List.of("Debate"))).isNull();
        assertThat(ActivityRecommender.recommend("PRESTRUCTURAL", "", List.of())).isNull();
    }

    @Test
    void recognisesMultistructuralSeparatelyFromUnistructural() {
        assertThat(ActivityRecommender.recommend(null, "MULTISTRUCTURAL", List.of())).isEqualTo("Q&A Session");
        assertThat(ActivityRecommender.recommend(null, "UNISTRUCTURAL", List.of())).isEqualTo("Quiz / Polls");
    }
}
