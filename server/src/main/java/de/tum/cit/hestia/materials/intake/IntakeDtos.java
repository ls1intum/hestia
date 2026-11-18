package de.tum.cit.hestia.materials.intake;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response shapes of the intake API. */
final class IntakeDtos {

    private IntakeDtos() {
    }

    record TermsResponse(String version, String sha256, String text) {
    }

    record MeResponse(String id, String suggestedEmail) {
    }

    record FileResponse(UUID id, String name, String contentType, long sizeBytes, String sha256) {
        static FileResponse from(IntakeFileInfo info) {
            return new FileResponse(info.id(), info.name(), info.contentType(), info.sizeBytes(), info.sha256());
        }
    }

    record SubmissionResponse(UUID id, String courseTitle, String contactEmail, String note, String termsVersion,
                              String termsSha256, boolean consentResearch, boolean consentReplicationPackage,
                              Instant createdAt, List<FileResponse> files) {
        static SubmissionResponse from(IntakeSubmission s, List<IntakeFileInfo> files) {
            return new SubmissionResponse(s.getId(), s.getCourseTitle(), s.getContactEmail(), s.getNote(),
                    s.getTermsVersion(), s.getTermsSha256(), s.isConsentResearch(), s.isConsentReplicationPackage(),
                    s.getCreatedAt(), files.stream().map(FileResponse::from).toList());
        }
    }
}
