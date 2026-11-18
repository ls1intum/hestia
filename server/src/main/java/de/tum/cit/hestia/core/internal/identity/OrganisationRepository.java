package de.tum.cit.hestia.core.internal.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrganisationRepository extends JpaRepository<Organisation, UUID> {

    Optional<Organisation> findBySchacHomeOrganization(String schacHomeOrganization);
}
