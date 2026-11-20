package de.tum.cit.hestia.materials.llm;

/**
 * Where {@link LlmClient} books the tokens of an answered chat call. Implemented outside this
 * package, so the LLM layer does not depend on what it is called for.
 */
public interface LlmUsageSink {

    /**
     * Books one call's tokens.
     *
     * @return false when nothing took them, so the client logs the call on its own.
     */
    boolean record(String model, int promptTokens, int completionTokens, int reasoningTokens);
}
