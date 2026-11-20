package de.tum.cit.hestia.ai.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ConsentEventRepository extends JpaRepository<ConsentEvent, UUID> {

    Optional<ConsentEvent> findFirstByUserIdAndScopeOrderByCreatedAtDesc(UUID userId, String scope);

    List<ConsentEvent> findByUserIdAndScopeOrderByCreatedAtAsc(UUID userId, String scope);
}
