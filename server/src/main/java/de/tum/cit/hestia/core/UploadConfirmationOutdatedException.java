package de.tum.cit.hestia.core;

/** A confirmation of a notice version that is no longer current (answered 409; reload and ask again). */
public class UploadConfirmationOutdatedException extends RuntimeException {

    public UploadConfirmationOutdatedException(String version) {
        super("The notice changed (confirmed version " + version + "); reload it and confirm again");
    }
}
