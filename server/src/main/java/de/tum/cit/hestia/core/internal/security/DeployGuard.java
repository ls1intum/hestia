package de.tum.cit.hestia.core.internal.security;

import java.util.Collection;

/**
 * Startup check that keeps the development login away from deployments. A deployed server knows its
 * deploy target from {@code HESTIA_DEPLOY_TARGET} ({@code Staging} or {@code Production}, set by
 * {@code infra/compose.prod.yaml} from the deploy's {@code ENVIRONMENT}), independent of the active
 * profiles, which {@code SPRING_PROFILES_ACTIVE} could override.
 *
 * <ul>
 *   <li>Profile {@code local} together with {@code prod}, or on the Production target: refuse.</li>
 *   <li>Header impersonation of the development login on any deploy target: refuse. Staging may run
 *       the development login, but only as its one fixed user.</li>
 * </ul>
 */
final class DeployGuard {

    private DeployGuard() {}

    static void check(Collection<String> activeProfiles, String deployTarget, boolean impersonation) {
        boolean devLogin = activeProfiles.contains("local");
        boolean deployed = deployTarget != null && !deployTarget.isBlank();
        if (devLogin && activeProfiles.contains("prod")) {
            throw new IllegalStateException("The development login (profile 'local') must not run with profile 'prod'.");
        }
        if (devLogin && deployed && deployTarget.trim().equalsIgnoreCase("Production")) {
            throw new IllegalStateException("The development login (profile 'local') must not run on Production.");
        }
        if (devLogin && deployed && impersonation) {
            throw new IllegalStateException(
                    "hestia.dev-login.impersonation must not be on in a deployment (HESTIA_DEPLOY_TARGET="
                            + deployTarget.trim() + ").");
        }
    }
}
