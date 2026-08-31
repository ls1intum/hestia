package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A terminal competency's Bloom level is classified from generated text, which reads as a summary of
 * the group beneath it. These cases pin the correction: the tree's own children decide the floor.
 */
class ExtractionRunnerTerminalBloomTest {

    @Test
    void raisesATerminalToTheHighestLevelAmongItsSubSkills() {
        TaxonomyClassification classified =
                new TaxonomyClassification(BloomLevel.UNDERSTAND, SoloLevel.RELATIONAL);

        TaxonomyClassification raised = ExtractionRunner.atLeastSubSkillBloom(
                classified, List.of(BloomLevel.UNDERSTAND, BloomLevel.ANALYZE, BloomLevel.APPLY));

        assertThat(raised.bloom()).isEqualTo(BloomLevel.ANALYZE);
        assertThat(raised.solo()).isEqualTo(SoloLevel.RELATIONAL);
    }

    @Test
    void keepsAClassificationThatAlreadyOutranksEverySubSkill() {
        TaxonomyClassification classified =
                new TaxonomyClassification(BloomLevel.EVALUATE, SoloLevel.EXTENDED_ABSTRACT);

        assertThat(ExtractionRunner.atLeastSubSkillBloom(
                classified, List.of(BloomLevel.UNDERSTAND, BloomLevel.APPLY)))
                .isSameAs(classified);
    }

    /** Sub-skills carry their levels from extraction, so an unclassified terminal still gets one. */
    @Test
    void fallsBackToTheSubSkillFloorWhenClassificationIsMissing() {
        TaxonomyClassification raised = ExtractionRunner.atLeastSubSkillBloom(
                null, List.of(BloomLevel.APPLY, BloomLevel.ANALYZE));

        assertThat(raised.bloom()).isEqualTo(BloomLevel.ANALYZE);
        assertThat(raised.solo()).isNull();
    }

    /** With nothing to raise it to, the classified level stands — including no classification at all. */
    @Test
    void leavesTheClassificationAloneWhenNoSubSkillCarriesALevel() {
        TaxonomyClassification classified =
                new TaxonomyClassification(BloomLevel.UNDERSTAND, SoloLevel.MULTISTRUCTURAL);

        assertThat(ExtractionRunner.atLeastSubSkillBloom(classified, List.of())).isSameAs(classified);
        assertThat(ExtractionRunner.atLeastSubSkillBloom(null, List.of())).isNull();
    }
}
