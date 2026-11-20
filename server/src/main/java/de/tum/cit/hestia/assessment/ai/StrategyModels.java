package de.tum.cit.hestia.assessment.ai;

import de.tum.cit.hestia.ai.AiExceptions;
import de.tum.cit.hestia.ai.ModelRef;
import de.tum.cit.hestia.ai.Provider;

/**
 * Maps a strategy's transport to the model reference the {@code ai} module understands. A
 * {@link ProviderKind#RETIRED} strategy has no counterpart there: it is refused here, before any
 * call is made.
 */
public final class StrategyModels {

    /** Thrown when something still references a withdrawn model. */
    public static final String RETIRED_MESSAGE =
        "This model has been retired and is no longer available.";

    private StrategyModels() {}

    public static ModelRef of(ParserStrategy strategy) {
        return of(strategy.providerKind(), strategy.providerModel());
    }

    public static ModelRef of(SolverStrategy strategy) {
        return of(strategy.providerKind(), strategy.providerModel());
    }

    public static ModelRef of(ProviderKind kind, String model) {
        return switch (kind) {
            case OPENAI -> ModelRef.of(Provider.OPENAI, model);
            case ANTHROPIC -> ModelRef.of(Provider.ANTHROPIC, model);
            case GEMINI -> ModelRef.of(Provider.GEMINI, model);
            // 410, not 500: AiExceptions.isTransient treats 5xx as retryable, and a
            // retired model must never be retried into a fallback that answers as a
            // different model than the exam records.
            case RETIRED -> throw new AiExceptions.ProviderException(RETIRED_MESSAGE, 410);
        };
    }

    /** The stable provider name persisted on generated rows (for example "openai"). */
    public static String providerName(Provider provider) {
        return provider.name().toLowerCase(java.util.Locale.ROOT);
    }
}
