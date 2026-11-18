/**
 * Domain module {@code assessment} (formerly the ExamLense server). May use {@code core} and its ports in
 * {@code core.spi}, never another domain module.
 */
@ApplicationModule(allowedDependencies = {"core", "core :: spi"})
package de.tum.cit.hestia.assessment;

import org.springframework.modulith.ApplicationModule;
