package de.tum.cit.hestia.assessment.storage;

import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * The upload limit of this module. The server-wide multipart limit is sized for the largest
 * upload of any module; ExamLense accepts files up to 10 MB (as its client's precheck does) and
 * answers a larger one as Spring's multipart limit did before: with
 * {@link MaxUploadSizeExceededException}, which the container turns into 413.
 */
public final class UploadLimit {

    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private UploadLimit() {}

    public static void check(MultipartFile file) {
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new MaxUploadSizeExceededException(MAX_FILE_BYTES);
        }
    }
}
