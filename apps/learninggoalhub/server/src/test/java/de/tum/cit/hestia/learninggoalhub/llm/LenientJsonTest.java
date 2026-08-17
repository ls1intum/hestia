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
}
