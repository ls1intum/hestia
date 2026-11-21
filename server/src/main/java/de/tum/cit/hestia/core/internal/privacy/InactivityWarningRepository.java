package de.tum.cit.hestia.core.internal.privacy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Warnings of the current organisation only (tenant filter). */
interface InactivityWarningRepository extends JpaRepository<InactivityWarning, UUID> {

    /** The warnings of the inactive period that began at {@code lastSeenAt}. */
    List<InactivityWarning> findByUserIdAndLastSeenAt(UUID userId, Instant lastSeenAt);

    List<InactivityWarning> findByUserIdAndAcknowledgedAtIsNullOrderByCreatedAtDesc(UUID userId);
}
