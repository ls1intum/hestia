package de.tum.cit.hestia.assessment.ai;

import static de.tum.cit.hestia.assessment.ai.ProviderKind.ANTHROPIC;
import static de.tum.cit.hestia.assessment.ai.ProviderKind.GEMINI;
import static de.tum.cit.hestia.assessment.ai.ProviderKind.OPENAI;
import static de.tum.cit.hestia.assessment.ai.ProviderKind.RETIRED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.tum.cit.hestia.ai.AiExceptions;
import de.tum.cit.hestia.ai.ModelRef;
import de.tum.cit.hestia.ai.Provider;
import org.junit.jupiter.api.Test;

class StrategyModelsTest {

    @Test
    void mapsTheThreeTransportsToTheExternalProviders() {
        assertThat(StrategyModels.of(OPENAI, "gpt-5.5")).isEqualTo(ModelRef.of(Provider.OPENAI, "gpt-5.5"));
        assertThat(StrategyModels.of(ANTHROPIC, "claude-opus-4-8")).isEqualTo(ModelRef.of(Provider.ANTHROPIC, "claude-opus-4-8"));
        assertThat(StrategyModels.of(GEMINI, "gemini-3.5-flash").external()).isTrue();
        assertThat(StrategyModels.providerName(Provider.GEMINI)).isEqualTo("gemini");
    }

    @Test
    void retiredModelIsRefusedRatherThanCalled() {
        assertThatThrownBy(() -> StrategyModels.of(RETIRED, "qwen3.6-35b-a3b"))
            .isInstanceOf(AiExceptions.ProviderException.class)
            .hasMessage(StrategyModels.RETIRED_MESSAGE);
    }

    /**
     * The status matters as much as the throw. A transient classification would send the parse
     * fallback to GPT-5.5 and re-enter the solve retry loop, so an exam would quietly be answered by a
     * model other than the one it records.
     */
    @Test
    void retiredModelFailureIsNotTransient() {
        AiExceptions.ProviderException e = catchThrowableOfType(
            () -> StrategyModels.of(RETIRED, "qwen3.6-35b-a3b"), AiExceptions.ProviderException.class);

        assertThat(e.status()).isEqualTo(410);
        assertThat(AiExceptions.isTransient(e)).isFalse();
    }

    @Test
    void everyStrategyOfTheCatalogMapsOrIsRetired() {
        for (ParserStrategy s : ParserStrategies.all()) {
            assertThat(StrategyModels.of(s).id()).isEqualTo(s.providerModel());
        }
        for (SolverStrategy s : SolverStrategies.all()) {
            assertThat(StrategyModels.of(s).id()).isEqualTo(s.providerModel());
        }
    }
}
