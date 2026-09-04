package de.tum.cit.hestia.learninggoalhub.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

class LenientJsonTest {

    record Outcome(String text) {
    }

    @Test
    void readsLatexBackslashesModelsLeaveUnescaped() {
        // Real failure mode: a model writing inline maths emits \( inside the JSON string,
        // which the strict parser rejects — losing every outcome in the reply, not just this one.
        List<Outcome> outcomes = LenientJson.converter(new ParameterizedTypeReference<List<Outcome>>() {})
                .convert("[{\"text\": \"Wende \\(x^2\\) an\"}, {\"text\": \"Zweites Ergebnis\"}]");

        assertThat(outcomes).extracting(Outcome::text)
                .containsExactly("Wende (x^2) an", "Zweites Ergebnis");
    }

    @Test
    void keepsProperlyEscapedBackslashes() {
        Outcome outcome = LenientJson.converter(Outcome.class)
                .convert("{\"text\": \"Erkläre \\\\frac{a}{b}\"}");

        assertThat(outcome.text()).isEqualTo("Erkläre \\frac{a}{b}");
    }

    @Test
    void ignoresUnknownPropertiesLikeSpringAisOwnConverter() {
        Outcome outcome = LenientJson.converter(Outcome.class)
                .convert("{\"text\": \"Ergebnis\", \"unexpected\": 1}");

        assertThat(outcome.text()).isEqualTo("Ergebnis");
    }

    record Cited(String text, Integer sourceFigure) {
    }

    /**
     * Real failure mode: a model emitted "sourceFigure" twice inside one knowledge item. Binding a
     * record through its canonical constructor cannot take a creator property twice, so the whole
     * session failed to parse, left nothing to salvage, and aborted the course run.
     */
    @Test
    void collapsesAKeyTheModelWroteTwiceInTheSameObject() {
        Cited cited = LenientJson.converter(Cited.class)
                .convert("{\"text\": \"Ergebnis\", \"sourceFigure\": 0, \"sourceFigure\": 0}");

        assertThat(cited.text()).isEqualTo("Ergebnis");
        assertThat(cited.sourceFigure()).isZero();
    }

    /** With two different values the later one wins, as a plain JSON reader would resolve it. */
    @Test
    void keepsTheLastValueOfARepeatedKey() {
        Cited cited = LenientJson.converter(Cited.class)
                .convert("{\"text\": \"Ergebnis\", \"sourceFigure\": 1, \"sourceFigure\": 2}");

        assertThat(cited.sourceFigure()).isEqualTo(2);
    }

    /** Collapsing runs on a tree, so a duplicate nested inside a list element is handled too. */
    @Test
    void collapsesARepeatedKeyInsideANestedObject() {
        record Parent(String text, List<Cited> knowledge) {
        }

        Parent parent = LenientJson.converter(Parent.class)
                .convert("{\"text\": \"Oberziel\", \"knowledge\": "
                        + "[{\"text\": \"Detail\", \"sourceFigure\": 0, \"sourceFigure\": 0}]}");

        assertThat(parent.knowledge()).singleElement()
                .satisfies(child -> assertThat(child.sourceFigure()).isZero());
    }

    /** Text that is not JSON at all still fails in the converter, not in the cleaner. */
    @Test
    void leavesTextThatIsNotJsonToTheConverter() {
        assertThat(LenientJson.collapseDuplicateKeys("not json at all"))
                .isEqualTo("not json at all");
    }

    /** The markdown fences Spring AI's own default chain strips are still stripped. */
    @Test
    void stillStripsMarkdownFences() {
        Outcome outcome = LenientJson.converter(Outcome.class)
                .convert("```json\n{\"text\": \"Ergebnis\"}\n```");

        assertThat(outcome.text()).isEqualTo("Ergebnis");
    }
}
