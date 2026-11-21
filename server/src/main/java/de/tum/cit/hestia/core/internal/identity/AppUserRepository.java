package de.tum.cit.hestia.core.internal.identity;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Users of the current organisation only (tenant filter). */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByExternalId(String externalId);

    /** Users whose last login (or, without one, the account's creation) is at or before {@code cutoff}. */
    @Query("select u from AppUser u where coalesce(u.lastSeenAt, u.createdAt) <= :cutoff order by u.id")
    List<AppUser> findInactiveSince(Instant cutoff);

    /** Locks the user row until the transaction ends; a login of the same user waits for it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> lockById(UUID id);
}
