/**
 * Domain module {@code teaching} (formerly the Workshopper server). May use {@code core} and its ports in
 * {@code core.spi}, never another domain module.
 */
@ApplicationModule(allowedDependencies = {"core", "core :: spi"})
package de.tum.cit.hestia.teaching;

import org.springframework.modulith.ApplicationModule;
