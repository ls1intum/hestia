package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TopicTreeSynthesizerTest {

    @Test
    void namesAreTrimmedAndDeduplicatedIgnoringCase() {
        assertThat(TopicTreeSynthesizer.normalizeNames(new TopicTreeSynthesizer.TopicNames(
                Arrays.asList(" Decision tree learning ", "decision tree learning", "", null, "Kernel methods"))))
                .containsExactly("Decision tree learning", "Kernel methods");
    }

    @Test
    void assignmentKeepsOnlyTheBatchAndTheFirstAnswerPerOutcome() {
        Map<Integer, Integer> assignment = TopicTreeSynthesizer.normalizeAssignments(
                new TopicTreeSynthesizer.Assignments(List.of(
                        List.of(40, 1), List.of(40, 2), List.of(41, -1), List.of(42, 9),
                        List.of(3, 0), List.of(43))),
                40, 80, 3);

        // 40 keeps its first topic, 41 is unmatched, 42 names a topic outside the menu, 3 lies outside
        // the batch and the one-element pair is not an answer.
        assertThat(assignment).containsExactly(
                Map.entry(40, 1), Map.entry(41, -1), Map.entry(42, -1));
    }

    @Test
    void omittedAndUnmatchedOutcomesStayOutOfEveryTopic() {
        Map<Integer, Integer> assignment = Map.of(0, 1, 1, -1, 3, 0, 4, 1);

        assertThat(TopicTreeSynthesizer.membersByTopic(assignment, 5, 2))
                .containsExactly(List.of(3), List.of(0, 4));
        assertThat(TopicTreeSynthesizer.unmatched(assignment, 5)).containsExactly(1, 2);
    }

    @Test
    void structureMapsGroupsBackToCourseIndicesAndKeepsTheRestDirect() {
        List<Integer> members = List.of(10, 11, 12, 13, 14);

        TopicTreeSynthesizer.PlannedTopic topic = TopicTreeSynthesizer.structureTopic("Boosting", members,
                new TopicTreeSynthesizer.TopicStructure(List.of(
                        new TopicTreeSynthesizer.CapabilityGroup("Explain gradient boosting", List.of(3, 1)),
                        new TopicTreeSynthesizer.CapabilityGroup("Explain AdaBoost", List.of(0))),
                        List.of(0, 2)));

        assertThat(topic.label()).isEqualTo("Boosting");
        assertThat(topic.capabilities()).singleElement().satisfies(capability -> {
            assertThat(capability.name()).isEqualTo("Explain gradient boosting");
            assertThat(capability.outcomes()).containsExactly(11, 13);
        });
        // The singleton group dissolves, and outcome 4 was left out of the answer entirely.
        assertThat(topic.direct()).containsExactly(10, 12, 14);
    }

    @Test
    void anOutcomeClaimedTwiceStaysWithItsFirstGroup() {
        TopicTreeSynthesizer.PlannedTopic topic = TopicTreeSynthesizer.structureTopic("Kernels",
                List.of(0, 1, 2, 3),
                new TopicTreeSynthesizer.TopicStructure(List.of(
                        new TopicTreeSynthesizer.CapabilityGroup("Explain the kernel trick", List.of(0, 1)),
                        new TopicTreeSynthesizer.CapabilityGroup("Choose a kernel width", List.of(1, 2, 3, 7))),
                        List.of()));

        assertThat(topic.capabilities()).extracting(TopicTreeSynthesizer.PlannedCapability::outcomes)
                .containsExactly(List.of(0, 1), List.of(2, 3));
        assertThat(topic.direct()).isEmpty();
    }

    @Test
    void aGroupHoldingTheWholeTopicIsTheTopicItself() {
        TopicTreeSynthesizer.PlannedTopic topic = TopicTreeSynthesizer.structureTopic("Curse of dimensionality",
                List.of(5, 6, 7),
                new TopicTreeSynthesizer.TopicStructure(List.of(
                        new TopicTreeSynthesizer.CapabilityGroup("Explain high-dimensional geometry", List.of(0, 1, 2))),
                        List.of()));

        assertThat(topic.capabilities()).isEmpty();
        assertThat(topic.direct()).containsExactly(5, 6, 7);
    }

    @Test
    void aBlankNameOrAMissingAnswerLeavesEveryOutcomeDirect() {
        assertThat(TopicTreeSynthesizer.structureTopic("Topic", List.of(1, 2, 3),
                new TopicTreeSynthesizer.TopicStructure(List.of(
                        new TopicTreeSynthesizer.CapabilityGroup(" ", List.of(0, 1))), null)).direct())
                .containsExactly(1, 2, 3);
        assertThat(TopicTreeSynthesizer.structureTopic("Topic", List.of(1, 2), null).direct())
                .containsExactly(1, 2);
    }

    @Test
    void shortLabelsAlignToTheNamesAndDropWhatDidNotShorten() {
        List<String> labels = TopicTreeSynthesizer.normalizeShortLabels(new TopicTreeSynthesizer.ShortLabels(
                Arrays.asList(
                        new TopicTreeSynthesizer.ShortLabel(2, " Compute the cost of capital. "),
                        new TopicTreeSynthesizer.ShortLabel(0, "Explain boosting"),
                        new TopicTreeSynthesizer.ShortLabel(0, "Explain boosting again"),
                        new TopicTreeSynthesizer.ShortLabel(1, "Analyse and adjust the equity beta using CAPM and the Hamada equation"),
                        new TopicTreeSynthesizer.ShortLabel(7, "Out of range"),
                        new TopicTreeSynthesizer.ShortLabel(3, " "),
                        null)),
                4);

        // Index 0 keeps its first answer, 1 is longer than a short label may be, 3 is blank.
        assertThat(labels).containsExactly("Explain boosting", null, "Compute the cost of capital", null);
        assertThat(TopicTreeSynthesizer.normalizeShortLabels(null, 2)).containsExactly(null, null);
    }

    @Test
    void shortLabelsFollowTheCapabilitiesAcrossTopics() {
        List<TopicTreeSynthesizer.PlannedTopic> topics = List.of(
                new TopicTreeSynthesizer.PlannedTopic("Valuation", List.of(
                        new TopicTreeSynthesizer.PlannedCapability("Estimate free cash flows for a DCF", List.of(0, 1)),
                        new TopicTreeSynthesizer.PlannedCapability("Derive values from DCF results", List.of(2, 3))),
                        List.of(4)),
                new TopicTreeSynthesizer.PlannedTopic("Direct only", List.of(), List.of(5, 6)),
                new TopicTreeSynthesizer.PlannedTopic("Options", List.of(
                        new TopicTreeSynthesizer.PlannedCapability("Price options with basic models", List.of(7, 8))),
                        List.of()));

        List<TopicTreeSynthesizer.PlannedTopic> labelled = TopicTreeSynthesizer.applyShortLabels(topics,
                Arrays.asList("Estimate free cash flows", null, "Price options"));

        assertThat(labelled).flatExtracting(TopicTreeSynthesizer.PlannedTopic::capabilities)
                .extracting(TopicTreeSynthesizer.PlannedCapability::shortLabel)
                .containsExactly("Estimate free cash flows", null, "Price options");
        assertThat(labelled.getFirst().capabilities().getFirst().outcomes()).containsExactly(0, 1);
        assertThat(labelled.getFirst().direct()).containsExactly(4);
    }
}
