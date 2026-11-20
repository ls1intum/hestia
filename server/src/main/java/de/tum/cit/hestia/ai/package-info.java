/**
 * Module {@code ai}: the only module that talks to a model. Calls go through {@link
 * de.tum.cit.hestia.ai.LlmGateway}, run on the TUM AET Logos gateway by default, and are logged with
 * user, course, model and tokens. Commercial providers outside TUM are called directly only for
 * models Logos does not offer, and only with the user's consent ({@link de.tum.cit.hestia.ai.ConsentApi}).
 * Uses {@code core}.
 */
@ApplicationModule(allowedDependencies = {"core"})
package de.tum.cit.hestia.ai;

import org.springframework.modulith.ApplicationModule;
