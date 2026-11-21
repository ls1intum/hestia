package de.tum.cit.hestia.core.internal.identity;

import de.tum.cit.hestia.core.OrgContext;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a verified login (SAML assertion or development login) into a {@link HestiaPrincipal}: finds
 * or creates the organisation and the user. Platform admins are the external ids in
 * {@code hestia.security.platform-admins}: only through the home (TUM) login, and for a scoped id
 * ({@code user@scope}) only when the login's organisation is that scope. A login through another
 * university of the federation is never a platform admin.
 */
@Service
public class IdentityService {

    private final OrganisationRepository organisations;
    private final AppUserRepository users;
    private final TransactionTemplate tx;
    private final Clock clock = Clock.systemUTC();
    private final Set<String> platformAdmins;

    IdentityService(OrganisationRepository organisations, AppUserRepository users, TransactionTemplate tx,
            @Value("${hestia.security.platform-admins:}") List<String> platformAdmins) {
        this.organisations = organisations;
        this.users = users;
        this.tx = tx;
        this.platformAdmins = platformAdmins.stream().map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * @param schacHomeOrganization the user's university, e.g. its domain
     * @param externalId            eduPersonPrincipalName, fallback the SAML NameID
     */
    public HestiaPrincipal login(String schacHomeOrganization, String externalId, String displayName) {
        return login(schacHomeOrganization, externalId, displayName, null);
    }

    /**
     * As {@link #login(String, String, String)}, with the login's e-mail attribute (kept in the
     * session only). The home (TUM) login: may make a platform admin.
     */
    public HestiaPrincipal login(String schacHomeOrganization, String externalId, String displayName, String email) {
        return login(schacHomeOrganization, externalId, displayName, email, null, true);
    }

    /**
     * A login through an identity provider of the federation: never a platform admin.
     * {@code organisationName} (the identity provider's name in the federation metadata) names a new
     * organisation, or one without a name.
     */
    public HestiaPrincipal federatedLogin(String schacHomeOrganization, String externalId, String displayName,
            String email, String organisationName) {
        return login(schacHomeOrganization, externalId, displayName, email, organisationName, false);
    }

    private HestiaPrincipal login(String schacHomeOrganization, String externalId, String displayName, String email,
            String organisationName, boolean mayBeAdmin) {
        String org = schacHomeOrganization.trim().toLowerCase(Locale.ROOT);
        UUID orgId = retryOnce(() -> tx.execute(status -> {
            Organisation o = organisations.findBySchacHomeOrganization(org)
                    .orElseGet(() -> organisations.save(new Organisation(org)));
            o.nameIfUnnamed(organisationName);
            return o;
        })).getId();
        UUID previous = OrgContext.set(orgId);
        try {
            AppUser user = retryOnce(() -> tx.execute(status -> {
                AppUser u = users.findByExternalId(externalId).orElseGet(() -> users.save(new AppUser(externalId)));
                u.seen(displayName, clock.instant());
                return u;
            }));
            return new HestiaPrincipal(user.getId(), orgId, externalId, displayName,
                    mayBeAdmin && isPlatformAdmin(org, externalId), email);
        } finally {
            OrgContext.set(previous);
        }
    }

    /** Listed, and for a scoped id ({@code user@scope}) the login's organisation is that scope. */
    private boolean isPlatformAdmin(String org, String externalId) {
        if (!platformAdmins.contains(externalId)) {
            return false;
        }
        int at = externalId.lastIndexOf('@');
        return at < 0 || externalId.substring(at + 1).equalsIgnoreCase(org);
    }

    /** Two first logins at the same moment: the loser's insert fails on the unique key, the retry finds the row. */
    private static <T> T retryOnce(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException e) {
            return action.get();
        }
    }
}
