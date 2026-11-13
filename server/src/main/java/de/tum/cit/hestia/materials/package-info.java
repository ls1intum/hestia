/**
 * Domain module {@code materials} (formerly the LearningGoalHub server). May use {@code core} and its ports in
 * {@code core.spi}, never another domain module.
 */
@ApplicationModule(allowedDependencies = {"core", "core :: spi"})
package de.tum.cit.hestia.materials;

import org.springframework.modulith.ApplicationModule;
