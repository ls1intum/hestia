package de.tum.cit.hestia.ai.internal;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface LlmJobRepository extends JpaRepository<LlmJob, UUID> {

    @Query("select count(j) from LlmJob j where j.userId = :userId and j.purpose = :purpose and j.createdAt > :since")
    long countSince(@Param("userId") UUID userId, @Param("purpose") String purpose, @Param("since") OffsetDateTime since);
}
