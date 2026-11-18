package de.tum.cit.hestia.core;

/**
 * Order of the security filter chains while each module still has its own (see
 * {@code core.internal.security.CoreSecurityConfig}). A module chain matches only its URL prefix.
 */
public final class SecurityChainOrder {

    /** A module chain that handles the SAML login for its own client (assessment). */
    public static final int SAML_MODULE = 1;
    /** The module chains, one per URL prefix. */
    public static final int MODULE = 20;

    private SecurityChainOrder() {}
}
