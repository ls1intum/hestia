/**
 * Shared kernel of the Hestia server. Uses no other module. Its public surface is this package
 * plus {@code core.spi} (ports that domain modules implement) and, later, {@code core.events}.
 */
@ApplicationModule(allowedDependencies = {})
package de.tum.cit.hestia.core;

import org.springframework.modulith.ApplicationModule;
