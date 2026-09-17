package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.hestia.learninggoalhub.goal.BloomLevel;
import de.tum.cit.hestia.learninggoalhub.goal.SoloLevel;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyClassification;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A capability's levels are classified from its generated name, which reads as a summary of the
 * outcomes beneath it. These cases pin the correction: the members decide the floor.
 */
class ExtractionRunnerCapabilityLevelsTest {

    @Test
    void raisesACapabilityToTheHighestBloomLevelAmongItsMembers() {
        TaxonomyClassification classified =
                new TaxonomyClassification(BloomLevel.UNDERSTAND, SoloLevel.RELATIONAL);

        TaxonomyClassification raised = ExtractionRunner.atLeastChildLevels(classified,
                List.of(BloomLevel.UNDERSTAND, BloomLevel.ANALYZE, BloomLevel.APPLY), List.of());

        assertThat(raised.bloom()).isEqualTo(BloomLevel.ANALYZE);
        assertThat(raised.solo()).isEqualTo(SoloLevel.RELATIONAL);
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
    void fallsBackToTheMemberFloorWhenClassificationIsMissing() {
        TaxonomyClassification raised = ExtractionRunner.atLeastChildLevels(null,
                List.of(BloomLevel.APPLY), List.of(SoloLevel.MULTISTRUCTURAL, SoloLevel.UNISTRUCTURAL));

        assertThat(raised.bloom()).isEqualTo(BloomLevel.APPLY);
        assertThat(raised.solo()).isEqualTo(SoloLevel.MULTISTRUCTURAL);
    }

    /** With nothing to raise it to, the classified level stands — including no classification at all. */
    @Test
    void leavesTheClassificationAloneWhenNoMemberCarriesALevel() {
        TaxonomyClassification classified =
                new TaxonomyClassification(BloomLevel.UNDERSTAND, SoloLevel.MULTISTRUCTURAL);

        assertThat(ExtractionRunner.atLeastChildLevels(classified, List.of(), List.of())).isSameAs(classified);
        assertThat(ExtractionRunner.atLeastChildLevels(null, List.of(), List.of())).isNull();
    }
}
