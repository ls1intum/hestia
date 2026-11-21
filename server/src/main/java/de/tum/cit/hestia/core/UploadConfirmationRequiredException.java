package de.tum.cit.hestia.core;

/**
 * An upload before the user confirmed that it contains no personal data of others. Answered 428 with
 * {@code code: UPLOAD_CONFIRMATION_REQUIRED}, so a client can ask and repeat the upload.
 */
public class UploadConfirmationRequiredException extends RuntimeException {

    public static final String CODE = "UPLOAD_CONFIRMATION_REQUIRED";

    public UploadConfirmationRequiredException() {
        super("Please confirm first that your uploads contain no personal data of others");
    }
}
