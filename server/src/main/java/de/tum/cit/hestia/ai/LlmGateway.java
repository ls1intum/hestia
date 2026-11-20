package de.tum.cit.hestia.ai;

/**
 * The only way to call a model. Every answered call is logged with user, course, model and tokens. A
 * call to an external model ({@link Provider#external()}) is refused with
 * {@link ConsentRequiredException} unless its user has consented, before anything leaves the server.
 */
public interface LlmGateway {

    LlmResponse chat(LlmRequest request);
}
