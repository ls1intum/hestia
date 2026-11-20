package de.tum.cit.hestia.ai.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LlmCallRepository extends JpaRepository<LlmCall, UUID> {

    List<LlmCall> findByJobId(UUID jobId);
}
