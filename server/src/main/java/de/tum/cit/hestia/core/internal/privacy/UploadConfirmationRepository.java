package de.tum.cit.hestia.core.internal.privacy;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Confirmations of the current organisation only (tenant filter). */
interface UploadConfirmationRepository extends JpaRepository<UploadConfirmationEvent, UUID> {

    Optional<UploadConfirmationEvent> findFirstByUserIdAndNoticeVersionOrderByCreatedAtDesc(UUID userId,
            String noticeVersion);
}
