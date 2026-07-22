package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedKnowledge;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSubSkill;
import de.tum.cit.hestia.learninggoalhub.extraction.SubtreeSynthesizer.GeneratedSubtree;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubtreeSynthesizerTest {

    private static GeneratedKnowledge knowledge(String text) {
        return new GeneratedKnowledge(text, "Deployment Stages");
    }

    @Test
    void rejectsEmptySubtree() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubtreeSynthesizer.validate(new GeneratedSubtree(List.of())))
                .withMessageContaining("at least one sub-skill");
    }

    @Test
    void rejectsBlankSubSkill() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubtreeSynthesizer.validate(new GeneratedSubtree(List.of(
                        new GeneratedSubSkill("  ", "Config", List.of(knowledge("Knowledge")))))))
                .withMessageContaining("blank sub-skill");
    }

    @Test
    void rejectsSubSkillWithoutKnowledge() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubtreeSynthesizer.validate(new GeneratedSubtree(List.of(
                        new GeneratedSubSkill("Configure deployments.", "Config", List.of())))))
                .withMessageContaining("knowledge items");
    }

    @Test
    void rejectsDuplicateNodeText() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SubtreeSynthesizer.validate(new GeneratedSubtree(List.of(
                        new GeneratedSubSkill("Configure deployments.", "Config",
                                List.of(knowledge("Explain deployment stages."))),
                        new GeneratedSubSkill(" configure DEPLOYMENTS. ", "Config2",
                                List.of(knowledge("Identify release criteria.")))))))
                .withMessageContaining("duplicate node text");
    }

    @Test
    void trimsTextsAndKeepsShortLabels() {
        GeneratedSubtree result = SubtreeSynthesizer.validate(
                new GeneratedSubtree(List.of(
                        new GeneratedSubSkill(" Configure deployments. ", "  Deployment Config  ",
                                List.of(new GeneratedKnowledge(" Explain deployment stages. ", " Deployment Stages "))))));

        GeneratedSubSkill subSkill = result.subSkills().get(0);
        assertThat(subSkill.text()).isEqualTo("Configure deployments.");
        assertThat(subSkill.shortLabel()).isEqualTo("Deployment Config");
        assertThat(subSkill.knowledge()).singleElement().satisfies(k -> {
            assertThat(k.text()).isEqualTo("Explain deployment stages.");
            assertThat(k.shortLabel()).isEqualTo("Deployment Stages");
        });
    }

    @Test
    void blankShortLabelBecomesNull() {
        GeneratedSubtree result = SubtreeSynthesizer.validate(
                new GeneratedSubtree(List.of(
                        new GeneratedSubSkill("Configure deployments.", "  ",
                                List.of(new GeneratedKnowledge("Explain deployment stages.", null))))));

        assertThat(result.subSkills().get(0).shortLabel()).isNull();
        assertThat(result.subSkills().get(0).knowledge().get(0).shortLabel()).isNull();
    }
}
