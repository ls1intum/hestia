package de.tum.cit.hestia.core;

import java.util.UUID;

/**
 * The logged-in user of the current request. The only way modules learn who is calling. Every
 * method fails with an authentication error when nobody is logged in.
 */
public interface CurrentUser {

    UUID userId();

    UUID orgId();

    /** A platform admin ({@code hestia.security.platform-admins}); grants no additional data access. */
    boolean isAdmin();

    /** Name shown in the clients; may be null. */
    String displayName();

    /** The e-mail address the login sent (SAML attribute mail), as a suggestion; never stored. May be null. */
    String email();
}
