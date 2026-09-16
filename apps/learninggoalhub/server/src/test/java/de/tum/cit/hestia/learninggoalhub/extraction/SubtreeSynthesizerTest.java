package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedKnowledge;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSkill;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSubSkill;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSubtree;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubtreeSynthesizerTest {

    private static GeneratedKnowledge knowledge(String text) {
        return new GeneratedKnowledge(text, "Deployment Stages");
    }

    private static GeneratedSubSkill subSkill(String text, String knowledgeText) {
        return new GeneratedSubSkill(text, "Config", List.of(knowledge(knowledgeText)));
    }

    private static GeneratedSkill skill(String text, GeneratedSubSkill... subSkills) {
        return new GeneratedSkill(text, "Deploy", List.of(subSkills));
    }

    @Test
    void rejectsEmptySubtree() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubtreeSynthesizer.validate(new GeneratedSubtree(List.of())))
                .withMessageContaining("at least one skill");
    }

    @Test
    void rejectsBlankSkill() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubtreeSynthesizer.validate(new GeneratedSubtree(List.of(
                        skill("  ", subSkill("Configure deployments.", "Explain deployment stages."))))))
                .withMessageContaining("blank skill");
    }

    @Test
    void rejectsSkillWithoutSubSkills() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubtreeSynthesizer.validate(new GeneratedSubtree(List.of(
                        skill("Automate deployments")))))
                .withMessageContaining("needs sub-skills");
    }

    @Test
    void rejectsBlankSubSkill() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubtreeSynthesizer.validate(new GeneratedSubtree(List.of(
                        skill("Automate deployments", subSkill("  ", "Knowledge"))))))
                .withMessageContaining("blank sub-skill");
    }

    @Test
    void rejectsSubSkillWithoutKnowledge() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubtreeSynthesizer.validate(new GeneratedSubtree(List.of(
                        skill("Automate deployments",
                                new GeneratedSubSkill("Configure deployments.", "Config", List.of()))))))
                .withMessageContaining("knowledge items");
    }

    @Test
    void rejectsMoreThanFiveSkillsWithoutTruncatingTheResponse() {
        List<GeneratedSkill> skills = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> skill("Apply capability " + index,
                        subSkill("Configure part " + index + ".", "Explain concept " + index + ".")))
                .toList();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubtreeSynthesizer.validate(new GeneratedSubtree(skills)))
                .withMessageContaining("more than five skills");
    }

    @Test
    void rejectsDuplicateNodeTextAcrossLevels() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubtreeSynthesizer.validate(new GeneratedSubtree(List.of(
                        skill("Configure deployments.",
                                subSkill(" configure DEPLOYMENTS. ", "Explain deployment stages."))))))
                .withMessageContaining("duplicate node text");
    }

    @Test
    void trimsTextsAndKeepsShortLabels() {
        GeneratedSubtree result = SubtreeSynthesizer.validate(new GeneratedSubtree(List.of(
                new GeneratedSkill(" Automate deployments ", " Deployment Automation ", List.of(
                        new GeneratedSubSkill(" Configure deployments. ", "  Deployment Config  ",
                                List.of(new GeneratedKnowledge(" Explain deployment stages. ",
                                        " Deployment Stages "))))))));

        GeneratedSkill skill = result.skills().get(0);
        assertThat(skill.text()).isEqualTo("Automate deployments");
        assertThat(skill.shortLabel()).isEqualTo("Deployment Automation");
        GeneratedSubSkill subSkill = skill.subSkills().get(0);
        assertThat(subSkill.text()).isEqualTo("Configure deployments.");
        assertThat(subSkill.shortLabel()).isEqualTo("Deployment Config");
        assertThat(subSkill.knowledge()).singleElement().satisfies(k -> {
            assertThat(k.text()).isEqualTo("Explain deployment stages.");
            assertThat(k.shortLabel()).isEqualTo("Deployment Stages");
        });
    }

    @Test
    void blankShortLabelBecomesNull() {
        GeneratedSubtree result = SubtreeSynthesizer.validate(new GeneratedSubtree(List.of(
                new GeneratedSkill("Automate deployments", " ", List.of(
                        new GeneratedSubSkill("Configure deployments.", "  ",
                                List.of(new GeneratedKnowledge("Explain deployment stages.", null))))))));

        GeneratedSkill skill = result.skills().get(0);
        assertThat(skill.shortLabel()).isNull();
        assertThat(skill.subSkills().get(0).shortLabel()).isNull();
        assertThat(skill.subSkills().get(0).knowledge().get(0).shortLabel()).isNull();
    }
}
