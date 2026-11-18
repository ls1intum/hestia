package de.tum.cit.hestia.materials.intake;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface IntakeSubmissionRepository extends JpaRepository<IntakeSubmission, UUID> {

    List<IntakeSubmission> findBySubmitterIdOrderByCreatedAtDesc(String submitterId);

    List<IntakeSubmission> findAllByOrderByCreatedAtDesc();
}
