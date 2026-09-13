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

        TaxonomyClassification raised = ExtractionRunner.atLeastChildBloom(
                classified, List.of(BloomLevel.UNDERSTAND, BloomLevel.ANALYZE, BloomLevel.APPLY));

        assertThat(raised.bloom()).isEqualTo(BloomLevel.ANALYZE);
        assertThat(raised.solo()).isEqualTo(SoloLevel.RELATIONAL);
    }

    @Test
    void keepsAClassificationThatAlreadyOutranksEverySubSkill() {
        TaxonomyClassification classified =
                new TaxonomyClassification(BloomLevel.EVALUATE, SoloLevel.EXTENDED_ABSTRACT);

        assertThat(ExtractionRunner.atLeastChildBloom(
                classified, List.of(BloomLevel.UNDERSTAND, BloomLevel.APPLY)))
                .isSameAs(classified);
    }

    /** Sub-skills carry their levels from extraction, so an unclassified terminal still gets one. */
    @Test
    void fallsBackToTheSubSkillFloorWhenClassificationIsMissing() {
        TaxonomyClassification raised = ExtractionRunner.atLeastChildBloom(
                null, List.of(BloomLevel.APPLY, BloomLevel.ANALYZE));

        assertThat(raised.bloom()).isEqualTo(BloomLevel.ANALYZE);
        assertThat(raised.solo()).isNull();
    }

    /** With nothing to raise it to, the classified level stands — including no classification at all. */
    @Test
    void leavesTheClassificationAloneWhenNoSubSkillCarriesALevel() {
        TaxonomyClassification classified =
                new TaxonomyClassification(BloomLevel.UNDERSTAND, SoloLevel.MULTISTRUCTURAL);

        assertThat(ExtractionRunner.atLeastChildBloom(classified, List.of())).isSameAs(classified);
        assertThat(ExtractionRunner.atLeastChildBloom(null, List.of())).isNull();
    }

    @Test
    void raisesACapabilityToTheHighestSoloLevelAmongItsMembers() {
        TaxonomyClassification classified =
                new TaxonomyClassification(BloomLevel.APPLY, SoloLevel.UNISTRUCTURAL);

        TaxonomyClassification raised = ExtractionRunner.atLeastChildLevels(classified,
                List.of(BloomLevel.APPLY), List.of(SoloLevel.UNISTRUCTURAL, SoloLevel.RELATIONAL));

        assertThat(raised.bloom()).isEqualTo(BloomLevel.APPLY);
        assertThat(raised.solo()).isEqualTo(SoloLevel.RELATIONAL);
    }

    /** Relating several simple outcomes is a higher structure than any one of them. */
    @Test
    void keepsAClassifiedSoloLevelAboveEveryMember() {
        TaxonomyClassification classified =
                new TaxonomyClassification(BloomLevel.ANALYZE, SoloLevel.RELATIONAL);

        assertThat(ExtractionRunner.atLeastChildLevels(classified,
                List.of(BloomLevel.APPLY), List.of(SoloLevel.UNISTRUCTURAL, SoloLevel.MULTISTRUCTURAL)))
                .isSameAs(classified);
    }

    @Test
    void fallsBackToTheMemberSoloFloorWhenClassificationIsMissing() {
        TaxonomyClassification raised = ExtractionRunner.atLeastChildLevels(null,
                List.of(BloomLevel.APPLY), List.of(SoloLevel.MULTISTRUCTURAL, SoloLevel.UNISTRUCTURAL));

        assertThat(raised.bloom()).isEqualTo(BloomLevel.APPLY);
        assertThat(raised.solo()).isEqualTo(SoloLevel.MULTISTRUCTURAL);
    }
}
