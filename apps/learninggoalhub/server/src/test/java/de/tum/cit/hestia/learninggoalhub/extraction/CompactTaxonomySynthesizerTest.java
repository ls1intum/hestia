package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompactTaxonomySynthesizerTest {

    @Test
    void acceptsAndOrdersAnExactSubSkillPartition() {
        List<CompactTaxonomySynthesizer.SubSkillGroup> groups =
                selected(
                        new CompactTaxonomySynthesizer.SubSkillPartition(subSkillPartition(18, 4).reversed(), List.of()),
                        72);

        assertThat(groups).hasSize(18);
        assertThat(groups).flatExtracting(CompactTaxonomySynthesizer.SubSkillGroup::supporting)
                .hasSize(72)
                .doesNotHaveDuplicates();
        assertThat(groups.getFirst().supporting()).containsExactly(0, 1, 2, 3);
    }

    @Test
    void rejectsASelectionThatLeavesAnOutcomeUnaccountedFor() {
        List<CompactTaxonomySynthesizer.SubSkillGroup> incomplete =
                new ArrayList<>(subSkillPartition(18, 4));
        CompactTaxonomySynthesizer.SubSkillGroup last = incomplete.getLast();
        incomplete.set(incomplete.size() - 1, new CompactTaxonomySynthesizer.SubSkillGroup(
                last.representative(), last.supporting().subList(0, 3)));

        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateSubSkillPartition(
                new CompactTaxonomySynthesizer.SubSkillPartition(incomplete, List.of()), 72, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 source outcome(s) unaccounted for");
    }

    @Test
    void rejectsASubSkillPartitionThatClaimsAnOutcomeTwice() {
        List<CompactTaxonomySynthesizer.SubSkillGroup> duplicated =
                new ArrayList<>(subSkillPartition(18, 4));
        duplicated.set(1, new CompactTaxonomySynthesizer.SubSkillGroup(0, List.of(0, 1, 2, 3)));

        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateSubSkillPartition(
                new CompactTaxonomySynthesizer.SubSkillPartition(duplicated, List.of()), 72, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact partition");
    }

    @Test
    void rejectsARepresentativeThatIsNotAMemberOfItsOwnGroup() {
        List<CompactTaxonomySynthesizer.SubSkillGroup> groups =
                new ArrayList<>(subSkillPartition(18, 4));
        groups.set(0, new CompactTaxonomySynthesizer.SubSkillGroup(70, groups.getFirst().supporting()));

        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateSubSkillPartition(
                new CompactTaxonomySynthesizer.SubSkillPartition(groups, List.of()), 72, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("elected representative [70]");
    }

    /**
     * The exact partition gives an outcome the model cannot place nowhere to go but a group, and
     * the count band alone never stopped it from being the nearest one: one real run returned five
     * groups of one beside a single group of nine covering five separate capabilities.
     */
    @Test
    void rejectsAGroupThatSweptUnplaceableOutcomesTogether() {
        // Fifty-six outcomes, the size of the course that produced the nine-member group. Nineteen
        // groups of two plus one of eighteen partitions them exactly and passes every other rule.
        List<CompactTaxonomySynthesizer.SubSkillGroup> swept =
                new ArrayList<>(subSkillPartition(19, 2));
        swept.add(new CompactTaxonomySynthesizer.SubSkillGroup(38,
                java.util.stream.IntStream.rangeClosed(38, 55).boxed().toList()));

        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateSubSkillPartition(
                new CompactTaxonomySynthesizer.SubSkillPartition(swept, List.of()), 56, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not hold more than 5 source outcomes")
                .hasMessageContaining("one holds 18");
    }

    /** The cap bounds a group, not the tree: spreading the same outcomes wider is what it asks for. */
    @Test
    void acceptsTheSameOutcomesOnceTheyAreSpreadAcrossGroups() {
        assertThat(selected(new CompactTaxonomySynthesizer.SubSkillPartition(
                subSkillPartition(14, 4), List.of()), 56)).hasSize(14);
        assertThat(selected(new CompactTaxonomySynthesizer.SubSkillPartition(
                subSkillPartition(12, 5), List.of()), 60)).hasSize(12);
    }

    /**
     * A flat cap is not always satisfiable, and an unsatisfiable rule does not reject a bad tree —
     * it rejects every tree. One corpus course carries 195 source outcomes, which cannot fit into
     * {@code MAX_SUB_SKILLS} groups of five however well they are grouped.
     */
    @Test
    void widensTheCapWhenTheSubSkillCeilingWouldMakeItUnsatisfiable() {
        assertThat(CompactTaxonomySynthesizer.maxMembersPerSubSkill(56)).isEqualTo(5);
        assertThat(CompactTaxonomySynthesizer.maxMembersPerSubSkill(120)).isEqualTo(5);
        assertThat(CompactTaxonomySynthesizer.maxMembersPerSubSkill(195)).isEqualTo(9);

        // 195 outcomes at the widened cap: a partition exists, so the stage can still succeed.
        assertThat(selected(new CompactTaxonomySynthesizer.SubSkillPartition(
                subSkillPartition(24, 8), List.of()), 192)).hasSize(24);
    }

    /**
     * The prompt is only rendered on a live run, so a placeholder that does not match its argument
     * would fail against SAIA rather than here. The cap must also reach the model: enforcing a rule
     * the prompt never states buys a retry where it could have bought a correct first answer.
     */
    @Test
    void rendersTheStageOnePromptWithTheGroupSizeCapInIt() {
        String prompt = CompactTaxonomySynthesizer.SUB_SKILL_PROMPT.formatted(
                "opening", "{\"subSkills\":[]}", 14, 24, "accounting",
                CompactTaxonomySynthesizer.maxMembersPerSubSkill(56), "conflict", "residue",
                "0. an outcome");

        assertThat(prompt).contains("5 is the hard maximum").contains("between 14 and 24 sub-skills");
    }

    /**
     * A doing-goal must not be elected away by an understanding-goal: the node names the performance
     * the group builds, and the terminal above it takes its level from these elections.
     */
    @Test
    void reElectsARepresentativeThatSitsBelowItsOwnGroupOnBloom() {
        List<CompactTaxonomySynthesizer.Candidate> candidates = List.of(
                new CompactTaxonomySynthesizer.Candidate("Understanding sparse grid concepts", "UNDERSTAND", "L1"),
                new CompactTaxonomySynthesizer.Candidate("Implementing sparse grid classification", "APPLY", "L2"));

        assertThat(CompactTaxonomySynthesizer.electHighestBloom(
                List.of(new CompactTaxonomySynthesizer.SubSkillGroup(0, List.of(0, 1))), candidates))
                .singleElement()
                .extracting(CompactTaxonomySynthesizer.SubSkillGroup::representative)
                .isEqualTo(1);
    }

    /** A pick already at the group's top level is left alone, so the model's naming judgement stands. */
    @Test
    void keepsARepresentativeThatAlreadySitsAtItsGroupsHighestLevel() {
        List<CompactTaxonomySynthesizer.Candidate> candidates = List.of(
                new CompactTaxonomySynthesizer.Candidate("Applying the transform, well worded", "APPLY", "L1"),
                new CompactTaxonomySynthesizer.Candidate("Applying the transform, awkwardly worded", "APPLY", "L2"),
                new CompactTaxonomySynthesizer.Candidate("Understanding the transform", "UNDERSTAND", "L3"));

        assertThat(CompactTaxonomySynthesizer.electHighestBloom(
                List.of(new CompactTaxonomySynthesizer.SubSkillGroup(0, List.of(0, 1, 2))), candidates))
                .singleElement()
                .extracting(CompactTaxonomySynthesizer.SubSkillGroup::representative)
                .isEqualTo(0);
    }

    /**
     * An outcome with no classified level cannot win an election it was never in, and a group of
     * only those keeps the pick it came with rather than failing.
     */
    @Test
    void ignoresUnrankableLevelsWhenReElecting() {
        List<CompactTaxonomySynthesizer.Candidate> candidates = List.of(
                new CompactTaxonomySynthesizer.Candidate("Elected without a level", null, "L1"),
                new CompactTaxonomySynthesizer.Candidate("Member with nonsense", "MASTERING", "L2"),
                new CompactTaxonomySynthesizer.Candidate("Member with a real level", "APPLY", "L3"));

        assertThat(CompactTaxonomySynthesizer.electHighestBloom(
                List.of(new CompactTaxonomySynthesizer.SubSkillGroup(0, List.of(0, 1))), candidates))
                .singleElement()
                .extracting(CompactTaxonomySynthesizer.SubSkillGroup::representative)
                .isEqualTo(0);
        // A rankable member outranks an unrankable elector, which is the case worth correcting.
        assertThat(CompactTaxonomySynthesizer.electHighestBloom(
                List.of(new CompactTaxonomySynthesizer.SubSkillGroup(0, List.of(0, 2))), candidates))
                .singleElement()
                .extracting(CompactTaxonomySynthesizer.SubSkillGroup::representative)
                .isEqualTo(2);
    }

    /**
     * The namer decides the verb and the stored Bloom is raised to the sub-skills' highest level
     * afterwards, so the level has to reach the namer or the two cannot agree.
     */
    @Test
    void showsTheNamerTheLevelEachGroupWillEndUpAt() {
        List<CompactTaxonomySynthesizer.Candidate> candidates = List.of(
                new CompactTaxonomySynthesizer.Candidate("Understanding quadrature error", "UNDERSTAND", "L1"),
                new CompactTaxonomySynthesizer.Candidate("Evaluating solver trade-offs", "EVALUATE", "L2"),
                new CompactTaxonomySynthesizer.Candidate("Unclassified outcome", null, "L3"));
        List<CompactTaxonomySynthesizer.SubSkillGroup> subSkills = List.of(
                new CompactTaxonomySynthesizer.SubSkillGroup(0, List.of(0)),
                new CompactTaxonomySynthesizer.SubSkillGroup(1, List.of(1)),
                new CompactTaxonomySynthesizer.SubSkillGroup(2, List.of(2)));

        // The group takes the HIGHEST level among its sub-skills, not the first or the average.
        assertThat(CompactTaxonomySynthesizer.groupLevel(List.of(0, 1), subSkills, candidates))
                .isEqualTo("EVALUATE");
        assertThat(CompactTaxonomySynthesizer.groupLevel(List.of(0), subSkills, candidates))
                .isEqualTo("UNDERSTAND");
        // Nothing to take a level from is left unstated rather than guessed at.
        assertThat(CompactTaxonomySynthesizer.groupLevel(List.of(2), subSkills, candidates)).isNull();
    }

    /** Every visible sub-skill must be a real extracted outcome, never generated text. */
    @Test
    void everySubSkillNodeIsAnElectedSourceOutcome() {
        List<CompactTaxonomySynthesizer.PlannedSkill> plan = CompactTaxonomySynthesizer.assemble(
                named(CompactTaxonomySynthesizer.validateTerminalGrouping(
                        terminalGrouping(3, 3, 3, 3, 3, 3), 18)),
                selected(
                        new CompactTaxonomySynthesizer.SubSkillPartition(subSkillPartition(18, 4), List.of()),
                        72));

        assertThat(plan).flatExtracting(CompactTaxonomySynthesizer.PlannedSkill::subSkills)
                .allSatisfy(subSkill -> assertThat(subSkill.supporting())
                        .contains(subSkill.representative()));
    }

    @Test
    void acceptsAnExactTerminalPartitionAndRejectsASixthSubSkill() {
        List<List<Integer>> terminals =
                CompactTaxonomySynthesizer.validateTerminalGrouping(
                        terminalGrouping(3, 3, 3, 3, 3, 3), 18);
        assertThat(terminals).hasSize(6);
        assertThat(terminals).flatExtracting(group -> group)
                .hasSize(18).doesNotHaveDuplicates();

        // Same eighteen sub-skills and the same allowed terminal count, but one terminal is too wide.
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateTerminalGrouping(
                terminalGrouping(6, 3, 3, 2, 2, 2), 18))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 5 sub-skills");
    }

    @Test
    void rejectsATerminalPartitionThatLeavesASubSkillUnplaced() {
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateTerminalGrouping(
                terminalGrouping(3, 3, 3, 3, 3, 3), 19))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 sub-skill(s) unplaced");
    }

    /**
     * The width cap must be met by merging, never by dropping: eighty outcomes still fit under six
     * terminals of at most five sub-skills each, because a sub-skill absorbs any number of outcomes.
     */
    @Test
    void compactsAnOverfullCourseWithoutLosingASingleOutcome() {
        List<CompactTaxonomySynthesizer.SubSkillGroup> groups =
                selected(
                        new CompactTaxonomySynthesizer.SubSkillPartition(subSkillPartition(20, 4), List.of()),
                        80);
        List<CompactTaxonomySynthesizer.TerminalGroup> terminals = named(
                CompactTaxonomySynthesizer.validateTerminalGrouping(terminalGrouping(5, 5, 4, 3, 3), 20));

        List<CompactTaxonomySynthesizer.PlannedSkill> plan =
                CompactTaxonomySynthesizer.assemble(terminals, groups);

        assertThat(plan).allSatisfy(skill -> assertThat(skill.subSkills())
                .hasSizeBetween(2, 5));
        assertThat(plan).flatExtracting(CompactTaxonomySynthesizer.PlannedSkill::subSkills)
                .flatExtracting(CompactTaxonomySynthesizer.PlannedSubSkill::supporting)
                .hasSize(80)
                .doesNotHaveDuplicates();
    }

    /** One stray early outcome must not drag a late chapter to the top of the course. */
    @Test
    void ordersTerminalSkillsByTheirMedianSourcePositionNotTheirEarliest() {
        CompactTaxonomySynthesizer.PlannedSkill lateChapterWithStray = new CompactTaxonomySynthesizer.PlannedSkill(
                "Applying the late chapter methods in representative contexts.", "Apply Late Chapter",
                List.of(new CompactTaxonomySynthesizer.PlannedSubSkill(90, List.of(0, 90, 91, 92))));
        CompactTaxonomySynthesizer.PlannedSkill earlyChapter = new CompactTaxonomySynthesizer.PlannedSkill(
                "Applying the early chapter methods in representative contexts.", "Apply Early Chapter",
                List.of(new CompactTaxonomySynthesizer.PlannedSubSkill(10, List.of(10, 11, 12))));

        assertThat(CompactTaxonomySynthesizer.medianSupporting(lateChapterWithStray)).isEqualTo(91);
        assertThat(CompactTaxonomySynthesizer.medianSupporting(earlyChapter)).isEqualTo(11);
    }

    @Test
    void scalesTheRequestedCountsDownForShortInputsAndKeepsTerminalsFeasible() {
        assertThat(CompactTaxonomySynthesizer.minSubSkills(86)).isEqualTo(18);
        assertThat(CompactTaxonomySynthesizer.maxSubSkills(86)).isEqualTo(24);
        assertThat(CompactTaxonomySynthesizer.minSubSkills(8)).isEqualTo(2);
        assertThat(CompactTaxonomySynthesizer.maxSubSkills(8)).isEqualTo(8);

        // A coherent tree slightly below the requested size is kept rather than retried into failure.
        assertThat(CompactTaxonomySynthesizer.minAcceptedSubSkills(86))
                .isEqualTo(6)
                .isLessThan(CompactTaxonomySynthesizer.minSubSkills(86));
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateSubSkillPartition(
                new CompactTaxonomySynthesizer.SubSkillPartition(subSkillPartition(2, 43), List.of()), 86, "English"))
                .hasMessageContaining("returned 2 sub-skills for 86 outcomes");

        // Whatever stage one returns, stage two must always have room for it within the width cap.
        for (int candidates = 1; candidates <= 400; candidates++) {
            int subSkills = CompactTaxonomySynthesizer.maxSubSkills(candidates);
            assertThat(CompactTaxonomySynthesizer.minTerminals(subSkills))
                    .isLessThanOrEqualTo(CompactTaxonomySynthesizer.maxTerminals(subSkills));
            assertThat(CompactTaxonomySynthesizer.maxTerminals(subSkills)
                    * CompactTaxonomySynthesizer.MAX_SUB_SKILLS_PER_SKILL)
                    .as("terminal capacity for %d sub-skills", subSkills)
                    .isGreaterThanOrEqualTo(subSkills);
        }
    }

    @Test
    void presentsTheCandidatesInAShuffledButReproducibleOrder() {
        List<Integer> order = CompactTaxonomySynthesizer.presentationOrder(86, 4711L);

        assertThat(order).hasSize(86).doesNotHaveDuplicates().containsAll(sourceIndices(86));
        // The model must not be shown the syllabus in sequence...
        assertThat(order).isNotEqualTo(sourceIndices(86));
        // ...but the same course must shuffle the same way twice, so two runs stay comparable.
        assertThat(CompactTaxonomySynthesizer.presentationOrder(86, 4711L)).isEqualTo(order);
        assertThat(CompactTaxonomySynthesizer.presentationOrder(86, 4712L)).isNotEqualTo(order);
    }

    /**
     * Grouping happens without lecture order; the finished tree still has it. A partition that is
     * consecutive in what the model saw must come back holding the scattered source outcomes it
     * really refers to, re-sorted into course order.
     */
    @Test
    void restoresLectureOrderAfterGroupingWithoutIt() {
        List<Integer> order = CompactTaxonomySynthesizer.presentationOrder(72, 99L);

        List<CompactTaxonomySynthesizer.SubSkillGroup> restored =
                CompactTaxonomySynthesizer.restoreSubSkillOrder(
                        selected(new CompactTaxonomySynthesizer.SubSkillPartition(
                                subSkillPartition(18, 4), List.of()), 72),
                        order);

        assertThat(restored).hasSize(18);
        assertThat(restored).flatExtracting(CompactTaxonomySynthesizer.SubSkillGroup::supporting)
                .hasSize(72).doesNotHaveDuplicates().containsAll(sourceIndices(72));
        // Every group holds the source outcomes its presented indices stood for.
        assertThat(restored).allSatisfy(group -> assertThat(group.supporting())
                .contains(group.representative()));
        assertThat(restored.stream()
                .map(group -> group.supporting().getFirst())
                .toList())
                .isSorted();
        // The first presented group was outcomes 0-3 as SHOWN, which are scattered in the course.
        assertThat(restored).anySatisfy(group -> assertThat(group.supporting())
                .containsExactlyInAnyOrder(order.get(0), order.get(1), order.get(2), order.get(3)));
    }

    @Test
    void restoresTerminalMembersOutOfPresentationSpace() {
        List<Integer> order = CompactTaxonomySynthesizer.presentationOrder(18, 7L);

        List<List<Integer>> restored =
                CompactTaxonomySynthesizer.restoreTerminalOrder(
                        CompactTaxonomySynthesizer.validateTerminalGrouping(
                                terminalGrouping(3, 3, 3, 3, 3, 3), 18),
                        order);

        assertThat(restored).hasSize(6);
        assertThat(restored).flatExtracting(group -> group)
                .hasSize(18).doesNotHaveDuplicates().containsAll(sourceIndices(18));
        assertThat(restored).anySatisfy(terminal -> assertThat(terminal)
                .containsExactlyInAnyOrder(order.get(0), order.get(1), order.get(2)));
    }

    private static List<Integer> sourceIndices(int count) {
        List<Integer> indices = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            indices.add(index);
        }
        return indices;
    }

    /**
     * Setting an outcome aside is a first-class answer, not a loss: it is still accounted for, it is
     * reported to the caller, and it is NOT force-fitted into whichever group happened to be nearest.
     */
    @Test
    void acceptsOutcomesSetAsideAsSupportingDetailAndReportsThem() {
        CompactTaxonomySynthesizer.SubSkillSelection selection =
                CompactTaxonomySynthesizer.validateSubSkillPartition(
                        new CompactTaxonomySynthesizer.SubSkillPartition(
                                subSkillPartition(16, 4), List.of(70, 64, 68, 71, 65, 69, 66, 67)),
                        72, "English");

        assertThat(selection.groups()).hasSize(16);
        assertThat(selection.setAside()).containsExactly(64, 65, 66, 67, 68, 69, 70, 71);
        assertThat(selection.groups()).flatExtracting(CompactTaxonomySynthesizer.SubSkillGroup::supporting)
                .hasSize(64)
                .doesNotHaveDuplicates()
                .doesNotContainAnyElementsOf(selection.setAside());
    }

    /** An outcome may not be both grouped and set aside, and none may go missing from the response. */
    @Test
    void rejectsAnOutcomeThatIsBothGroupedAndSetAside() {
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateSubSkillPartition(
                new CompactTaxonomySynthesizer.SubSkillPartition(subSkillPartition(18, 4), List.of(7)),
                72, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact partition");

        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateSubSkillPartition(
                new CompactTaxonomySynthesizer.SubSkillPartition(subSkillPartition(17, 4), List.of()),
                72, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4 source outcome(s) unaccounted for");
    }

    /** Selecting is the point; giving up is not. A model may not set aside most of the course. */
    @Test
    void rejectsASelectionThatSetsAsideMoreThanHalfTheCourse() {
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateSubSkillPartition(
                new CompactTaxonomySynthesizer.SubSkillPartition(
                        subSkillPartition(8, 4), sourceIndices(72).subList(32, 72)),
                72, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least half must become sub-skills");
    }

    @Test
    void restoresSetAsideIndicesIntoSourceOrderToo() {
        List<Integer> order = CompactTaxonomySynthesizer.presentationOrder(72, 5L);

        CompactTaxonomySynthesizer.SubSkillSelection restored =
                CompactTaxonomySynthesizer.restoreSubSkillOrder(
                        CompactTaxonomySynthesizer.validateSubSkillPartition(
                                new CompactTaxonomySynthesizer.SubSkillPartition(
                                        subSkillPartition(16, 4), List.of(64, 65, 66, 67, 68, 69, 70, 71)),
                                72, "English"),
                        order);

        assertThat(restored.setAside())
                .hasSize(8)
                .isSorted()
                .containsExactlyInAnyOrderElementsOf(
                        List.of(64, 65, 66, 67, 68, 69, 70, 71).stream().map(order::get).toList());
    }

    /**
     * With the residue disabled, a volunteered setAside is a validation failure rather than a silent
     * shrinking of the tree — the retry then carries the reason back to the model.
     */
    @Test
    void rejectsAResidueWhenSettingOutcomesAsideIsDisabled() {
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateSubSkillPartition(
                new CompactTaxonomySynthesizer.SubSkillPartition(
                        subSkillPartition(16, 4), List.of(64, 65, 66, 67, 68, 69, 70, 71)),
                72, "English", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setting outcomes aside is disabled");
    }

    /** The accounting comes first, so a double-claimed outcome still fails as a partition error. */
    @Test
    void rejectsAnOutcomeThatIsBothGroupedAndSetAsideEvenWhenTheResidueIsDisabled() {
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateSubSkillPartition(
                new CompactTaxonomySynthesizer.SubSkillPartition(subSkillPartition(18, 4), List.of(7)),
                72, "English", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exact partition");
    }

    /** Everything else about the stage is unchanged: a full partition passes under either policy. */
    @Test
    void acceptsAFullPartitionWhenSettingOutcomesAsideIsDisabled() {
        CompactTaxonomySynthesizer.SubSkillSelection selection =
                CompactTaxonomySynthesizer.validateSubSkillPartition(
                        new CompactTaxonomySynthesizer.SubSkillPartition(subSkillPartition(18, 4), List.of()),
                        72, "English", false);

        assertThat(selection.setAside()).isEmpty();
        assertThat(selection.groups()).hasSize(18)
                .flatExtracting(CompactTaxonomySynthesizer.SubSkillGroup::supporting)
                .hasSize(72)
                .doesNotHaveDuplicates();
    }

    /** Convenience for the many cases that assert on the groups alone. */
    private static List<CompactTaxonomySynthesizer.SubSkillGroup> selected(
            CompactTaxonomySynthesizer.SubSkillPartition partition, int candidateCount) {
        return CompactTaxonomySynthesizer.validateSubSkillPartition(partition, candidateCount, "English")
                .groups();
    }

    /**
     * How many terminal skills a course has is the model's call. The only thing the range protects
     * is that none of them is a single sub-skill wearing a second name.
     */
    @Test
    void letsTheModelChooseTheTerminalCountAndOnlyForbidsSingleChildSkills() {
        // Eighteen sub-skills: anywhere from four to nine terminals is a legitimate answer.
        assertThat(CompactTaxonomySynthesizer.minTerminals(18)).isEqualTo(4);
        assertThat(CompactTaxonomySynthesizer.maxTerminals(18)).isEqualTo(9);
        assertThat(CompactTaxonomySynthesizer.validateTerminalGrouping(
                terminalGrouping(5, 5, 4, 4), 18)).hasSize(4);
        assertThat(CompactTaxonomySynthesizer.validateTerminalGrouping(
                terminalGrouping(2, 2, 2, 2, 2, 2, 2, 2, 2), 18)).hasSize(9);

        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateTerminalGrouping(
                terminalGrouping(5, 5, 4, 3, 1), 18))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2 sub-skills");
    }

    /** A course with one sub-skill has nothing to pair it with, so the rule must not deadlock it. */
    @Test
    void allowsASingleSubSkillCourse() {
        assertThat(CompactTaxonomySynthesizer.validateTerminalGrouping(
                terminalGrouping(1), 1))
                .hasSize(1);
    }

    /** A well-formed naming set: every label drawn from its own group, and all of them distinct. */
    @Test
    void acceptsNamesDrawnFromTheirOwnGroupsThatStayDistinguishable() {
        List<CompactTaxonomySynthesizer.TerminalGroup> terminals =
                CompactTaxonomySynthesizer.validateTerminalNames(
                        naming(name("Analysing power series for their radius of convergence",
                                        "Analysing power series"),
                                name("Applying residue methods to evaluate contour integrals",
                                        "Applying residue methods")),
                        NAMED_GROUPS, NAMED_SUB_SKILLS, NAMED_CANDIDATES, "English");

        assertThat(terminals).extracting(CompactTaxonomySynthesizer.TerminalGroup::shortLabel)
                .containsExactly("Analysing power series", "Applying residue methods");
        assertThat(terminals).flatExtracting(CompactTaxonomySynthesizer.TerminalGroup::subSkills)
                .containsExactly(0, 1, 2, 3);
    }

    /**
     * The failure seen in a real run: two of five terminals named after the course's own subject,
     * telling a reader nothing about which capability they are looking at. Only a rule that reads
     * the labels TOGETHER can see it, since each one is unobjectionable on its own.
     */
    @Test
    void rejectsSiblingsThatNameTheSameThingAndDifferOnlyInTheirAction() {
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateTerminalNames(
                naming(name("Analysing power series and residue methods across the course",
                                "Analysing series methods"),
                        name("Applying power series and residue methods across the course",
                                "Applying series methods")),
                NAMED_GROUPS, NAMED_SUB_SKILLS, NAMED_CANDIDATES, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ only in their action");
    }

    /** A label whose topic words appear nowhere beneath it was supplied, not derived. */
    @Test
    void rejectsALabelThatUsesNoTermFromTheOutcomesItCovers() {
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateTerminalNames(
                naming(name("Analysing stochastic gradient descent in optimisation problems",
                                "Analysing gradient descent"),
                        name("Applying residue methods to evaluate contour integrals",
                                "Applying residue methods")),
                NAMED_GROUPS, NAMED_SUB_SKILLS, NAMED_CANDIDATES, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no term from the outcomes it covers");
    }

    /**
     * The failure this cap was built for: a name that restates its whole group instead of naming it.
     * Measured on a real course, terminal text ran to 39 words on average over sub-skills of 11, and
     * one of them repeated its members' verb once per member.
     */
    @Test
    void rejectsATerminalNameThatSummarisesItsGroupInsteadOfNamingIt() {
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateTerminalNames(
                naming(name("Analysing the radius of convergence of power series, analysing the "
                                + "classification of isolated singularities, and analysing the "
                                + "behaviour of complex functions near those singularities in detail",
                                "Analysing power series"),
                        name("Applying residue methods to evaluate contour integrals",
                                "Applying residue methods")),
                NAMED_GROUPS, NAMED_SUB_SKILLS, NAMED_CANDIDATES, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summarises its group instead of naming it");
    }

    /** A name may spend every word the cap allows. */
    @Test
    void acceptsATerminalNameOfExactlyTheLongestPermittedLength() {
        String text = "Analysing power series"
                + " word".repeat(CompactTaxonomySynthesizer.MAX_TERMINAL_TEXT_WORDS - 3);
        assertThat(text.split("\\s+")).hasSize(CompactTaxonomySynthesizer.MAX_TERMINAL_TEXT_WORDS);

        assertThat(CompactTaxonomySynthesizer.validateTerminalNames(
                naming(name(text, "Analysing power series"),
                        name("Applying residue methods to evaluate contour integrals",
                                "Applying residue methods")),
                NAMED_GROUPS, NAMED_SUB_SKILLS, NAMED_CANDIDATES, "English"))
                .hasSize(2);
    }

    /** An action with nothing to act on is not a name. */
    @Test
    void rejectsALabelThatCarriesOnlyItsAction() {
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateTerminalNames(
                naming(name("Analysing the material of this part of the course thoroughly", "Analysing"),
                        name("Applying residue methods to evaluate contour integrals",
                                "Applying residue methods")),
                NAMED_GROUPS, NAMED_SUB_SKILLS, NAMED_CANDIDATES, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("names only an action");
    }

    /**
     * The wording invariant is now ENFORCED here rather than waived after one retry: naming is its
     * own call, so rejecting a name costs a regeneration instead of a validated partition.
     */
    @Test
    void rejectsAWordingViolationInsteadOfDowngradingItToAWarning() {
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateTerminalNames(
                naming(name("Analyse power series for their radius of convergence", "Analysing power series"),
                        name("Applying residue methods to evaluate contour integrals",
                                "Applying residue methods")),
                NAMED_GROUPS, NAMED_SUB_SKILLS, NAMED_CANDIDATES, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must begin with a gerund");
    }

    /** One name per group, in group order — a short or long list is a failure, not a truncation. */
    @Test
    void rejectsANamingThatDoesNotCoverEveryGroup() {
        assertThatThrownBy(() -> CompactTaxonomySynthesizer.validateTerminalNames(
                naming(name("Analysing power series for their radius of convergence", "Analysing power series")),
                NAMED_GROUPS, NAMED_SUB_SKILLS, NAMED_CANDIDATES, "English"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one name per group");
    }

    /**
     * Anchoring compares stems, because the label is an abstraction of the outcomes rather than a
     * quotation of them: German inflects and compounds, so "Integralmethoden" is anchored by
     * "Integralen" while a term from another subject is not.
     */
    @Test
    void anchorsLabelTermsByStemSoInflectionAndCompoundsStillCount() {
        Set<String> vocabulary = CompactTaxonomySynthesizer.significantTokens(
                "Anwenden des Hauptsatzes zur Berechnung von Integralen über geschlossene Wege");

        assertThat(CompactTaxonomySynthesizer.anchored("integralmethoden", vocabulary)).isTrue();
        assertThat(CompactTaxonomySynthesizer.anchored("hauptsatzes", vocabulary)).isTrue();
        assertThat(CompactTaxonomySynthesizer.anchored("regressionsmodelle", vocabulary)).isFalse();
    }

    /** The action is found via the text's opening word, so either German word order works. */
    @Test
    void stripsTheActionFromALabelWhicheverEndOfItTheActionSitsAt() {
        assertThat(CompactTaxonomySynthesizer.objectTokens(
                "Analysieren von Singularitäten", "Analysieren isolierter Singularitäten einer Funktion"))
                .containsExactly("singularitäten");
        assertThat(CompactTaxonomySynthesizer.objectTokens(
                "Potenzreihen analysieren", "Analysieren von Potenzreihen und ihrem Konvergenzradius"))
                .containsExactly("potenzreihen");
    }

    private static final List<List<Integer>> NAMED_GROUPS = List.of(List.of(0, 1), List.of(2, 3));

    private static final List<CompactTaxonomySynthesizer.SubSkillGroup> NAMED_SUB_SKILLS = List.of(
            new CompactTaxonomySynthesizer.SubSkillGroup(0, List.of(0)),
            new CompactTaxonomySynthesizer.SubSkillGroup(1, List.of(1)),
            new CompactTaxonomySynthesizer.SubSkillGroup(2, List.of(2)),
            new CompactTaxonomySynthesizer.SubSkillGroup(3, List.of(3)));

    private static final List<CompactTaxonomySynthesizer.Candidate> NAMED_CANDIDATES = List.of(
            candidate("Determining the radius of convergence of a power series"),
            candidate("Classifying isolated singularities of a complex function"),
            candidate("Evaluating a contour integral with the residue theorem"),
            candidate("Applying residue methods to infinite series"));

    private static CompactTaxonomySynthesizer.Candidate candidate(String text) {
        return new CompactTaxonomySynthesizer.Candidate(text, "APPLY", "Session 1");
    }

    private static CompactTaxonomySynthesizer.TerminalName name(String text, String shortLabel) {
        return new CompactTaxonomySynthesizer.TerminalName(text, shortLabel);
    }

    private static CompactTaxonomySynthesizer.TerminalNaming naming(
            CompactTaxonomySynthesizer.TerminalName... names) {
        return new CompactTaxonomySynthesizer.TerminalNaming(List.of(names));
    }

    /** Consecutive groups of {@code each} outcomes, each electing its own first member. */
    private static List<CompactTaxonomySynthesizer.SubSkillGroup> subSkillPartition(int groups, int each) {
        List<CompactTaxonomySynthesizer.SubSkillGroup> result = new ArrayList<>();
        for (int group = 0; group < groups; group++) {
            List<Integer> supporting = new ArrayList<>();
            for (int offset = 0; offset < each; offset++) {
                supporting.add(group * each + offset);
            }
            result.add(new CompactTaxonomySynthesizer.SubSkillGroup(supporting.getFirst(), supporting));
        }
        return result;
    }

    /** One terminal group per given width, numbering the sub-skills consecutively across them. */
    private static CompactTaxonomySynthesizer.TerminalGrouping terminalGrouping(int... widths) {
        List<CompactTaxonomySynthesizer.TerminalMembers> result = new ArrayList<>();
        int next = 0;
        for (int width : widths) {
            List<Integer> members = new ArrayList<>();
            for (int offset = 0; offset < width; offset++) {
                members.add(next++);
            }
            result.add(new CompactTaxonomySynthesizer.TerminalMembers(members));
        }
        return new CompactTaxonomySynthesizer.TerminalGrouping(result);
    }

    /** A validated grouping wearing placeholder names, for the cases that assert on structure. */
    private static List<CompactTaxonomySynthesizer.TerminalGroup> named(List<List<Integer>> groups) {
        List<CompactTaxonomySynthesizer.TerminalGroup> result = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            result.add(new CompactTaxonomySynthesizer.TerminalGroup(
                    "Applying method family " + (index + 1) + " in representative course contexts.",
                    "Apply Family " + (index + 1), groups.get(index)));
        }
        return result;
    }
}
