package de.tum.cit.hestia.materials.intake;

import java.util.UUID;

/** A file of a submission without its bytes. */
public record IntakeFileInfo(UUID id, UUID submissionId, String name, String contentType, long sizeBytes,
                             String sha256) {
}
