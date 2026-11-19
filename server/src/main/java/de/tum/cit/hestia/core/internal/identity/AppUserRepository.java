package de.tum.cit.hestia.core.internal.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Users of the current organisation only (tenant filter). */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByExternalId(String externalId);
}
