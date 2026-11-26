package de.tum.cit.hestia.core.internal.security;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;

/**
 * Startup check against Java4Shell (CVE-2026-114938): the AVX-512 Base64 intrinsic of HotSpot before
 * 21.0.13.1 overflows a heap buffer on crafted input, for example a SAMLResponse before login.
 *
 * <p>A deployed server (a {@code HESTIA_DEPLOY_TARGET} is set) refuses to start when the JVM is
 * older than 21.0.13.1 and the intrinsic is still on. {@code server/Dockerfile} pins a fixed JDK and
 * also switches the intrinsic off; this check reads the value the running JVM actually uses, so it
 * also catches an override through {@code _JAVA_OPTIONS} or a changed base image. Local development
 * and the tests are not checked.
 */
final class Java4ShellGuard {

    static final String FLAG = "UseBASE64Intrinsics";
    static final Runtime.Version FIXED = Runtime.Version.parse("21.0.13.1");

    private Java4ShellGuard() {}

    static void check(String deployTarget) {
        check(deployTarget, Runtime.version(), base64IntrinsicOn());
    }

    static void check(String deployTarget, Runtime.Version version, boolean intrinsicOn) {
        boolean deployed = deployTarget != null && !deployTarget.isBlank();
        if (deployed && intrinsicOn && !isFixed(version)) {
            throw new IllegalStateException("Java " + version + " is open to Java4Shell (CVE-2026-114938): run "
                    + FIXED + " or later, or start the JVM with -XX:-" + FLAG + " (see server/Dockerfile).");
        }
    }

    /** Only the 21 line is known to be fixed; any other feature release counts as open. */
    static boolean isFixed(Runtime.Version version) {
        return version.feature() == FIXED.feature() && version.compareToIgnoreOptional(FIXED) >= 0;
    }

    /** The value the running JVM uses; an unknown flag counts as on, to stay on the safe side. */
    private static boolean base64IntrinsicOn() {
        try {
            return Boolean.parseBoolean(ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .getVMOption(FLAG)
                    .getValue());
        } catch (IllegalArgumentException | NullPointerException e) {
            return true;
        }
    }
}
