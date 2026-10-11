package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;

class OutcomeWordingTest {

    @Test
    void acceptsEnglishGerundAndGermanNominalizedInfinitive() {
        assertThatNoException().isThrownBy(() -> OutcomeWording.validate(
                "Applying Cauchy's integral theorem to closed contours",
                "Apply Cauchy's Theorem", "English", "Skill"));
        assertThatNoException().isThrownBy(() -> OutcomeWording.validate(
                "Anwenden des Cauchy-Integraltheorems auf geschlossene Wege",
                "Cauchy-Integraltheorem anwenden", "German", "Skill"));
    }

    /**
     * German writes some actions as a noun and some as a nominalized infinitive. Demanding only the
     * latter made the generator invent forms ("Konstruktieren" for "Konstruktion"/"konstruieren"),
     * so both are accepted — while finite and imperative verbs are still rejected.
     */
    @Test
    void acceptsGermanActionNounsAsWellAsNominalizedInfinitives() {
        assertThatNoException().isThrownBy(() -> OutcomeWording.validate(
                "Konstruktion von Möbius-Transformationen anhand vorgegebener Punkte",
                "Möbius-Transformationen konstruieren", "German", "Skill"));
        assertThatNoException().isThrownBy(() -> OutcomeWording.validate(
                "Analyse von Potenzreihen hinsichtlich ihres Konvergenzradius",
                "Potenzreihen analysieren", "German", "Skill"));
        assertThatNoException().isThrownBy(() -> OutcomeWording.validate(
                "Anwendung des Residuensatzes zur Berechnung uneigentlicher Integrale",
                "Residuensatz anwenden", "German", "Skill"));

        assertThatIllegalArgumentException().isThrownBy(() -> OutcomeWording.validate(
                        "Wende den Residuensatz auf uneigentliche Integrale an",
                        "Residuensatz anwenden", "German", "Skill"))
                .withMessageContaining("must begin with an action noun");
    }

    /**
     * The split salvage relies on: a caption that repeats its outcome is a label defect, and the
     * outcome text beside it is still perfectly usable. One run aborted at 70% because every skill
     * in a session captioned itself this way and salvage dropped them all.
     */
    @Test
    void acceptsAnIdenticalLabelWhenOnlyTheOutcomeTextIsJudged() {
        OutcomeWording.validateOutcomeText(
                "Applying Cauchy's theorem to contour integrals", "Applying Cauchy's theorem to contour integrals",
                "English", "Skill");
    }

    /** A defect in the text itself is still fatal, however the label reads. */
    @Test
    void stillRejectsTextThatIsNotAnActionNounWhenOnlyTheOutcomeTextIsJudged() {
        assertThatIllegalArgumentException().isThrownBy(() -> OutcomeWording.validateOutcomeText(
                        "Apply Cauchy's theorem to contour integrals", "Apply Cauchy", "English", "Skill"))
                .withMessageContaining("gerund");
        assertThatIllegalArgumentException().isThrownBy(() -> OutcomeWording.validateOutcomeText(
                        "  ", "Apply Cauchy", "English", "Skill"))
                .withMessageContaining("non-blank text");
    }

    @Test
    void rejectsIdenticalShortAndLongTextInEveryLanguage() {
        assertThatIllegalArgumentException().isThrownBy(() -> OutcomeWording.validate(
                        "Apply Cauchy's theorem", "Apply Cauchy's theorem", "English", "Skill"))
                .withMessageContaining("must not be identical");
        assertThatIllegalArgumentException().isThrownBy(() -> OutcomeWording.validate(
                        "Cauchy-Integraltheorem anwenden", "Cauchy-Integraltheorem anwenden",
                        "German", "Skill"))
                .withMessageContaining("must not be identical");
    }

    @Test
    void auditedTaxonomyFallbackAllowsIdenticalAndNaturalActionNounForms() {
        assertThatNoException().isThrownBy(() -> OutcomeWording.validateAudited(
                "Applying Cauchy's theorem to closed contours",
                "Applying Cauchy's theorem to closed contours", "English", "Skill"));
        assertThatNoException().isThrownBy(() -> OutcomeWording.validateAudited(
                "Analyse komplexer Funktionen mithilfe des Cauchy-Integraltheorems",
                "Komplexe Funktionen analysieren", "German", "Skill"));
    }

    @Test
    void rejectsImperativeOrPersonalSentenceForms() {
        assertThatIllegalArgumentException().isThrownBy(() -> OutcomeWording.validate(
                        "Apply Cauchy's theorem to closed contours", "Apply Cauchy's Theorem",
                        "English", "Skill"))
                .withMessageContaining("gerund");
        assertThatIllegalArgumentException().isThrownBy(() -> OutcomeWording.validate(
                        "Verstehen Sie den Zusammenhang zwischen den Abbildungen",
                        "Zusammenhang verstehen", "German", "Skill"))
                .withMessageContaining("must not address");
        assertThatIllegalArgumentException().isThrownBy(() -> OutcomeWording.validate(
                        "Modelliert kombinatorische Strukturen mit erzeugenden Funktionen",
                        "Strukturen modellieren", "German", "Skill"))
                .withMessageContaining("must begin with an action noun");
    }
}
