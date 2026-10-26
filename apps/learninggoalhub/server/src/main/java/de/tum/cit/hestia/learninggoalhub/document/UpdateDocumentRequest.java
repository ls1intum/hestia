package de.tum.cit.hestia.learninggoalhub.document;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A partial update. {@code displayName} needs to tell "left out" from "null": null clears the
 * display name, while a body that only changes the kind must not.
 */
public class UpdateDocumentRequest {

    private String displayName;
    private boolean displayNameSet;
    private DocumentKind kind;

    @Schema(nullable = true)
    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        this.displayNameSet = true;
    }

    @Schema(nullable = true)
    public DocumentKind getKind() {
        return kind;
    }

    public void setKind(DocumentKind kind) {
        this.kind = kind;
    }

    boolean hasDisplayName() {
        return displayNameSet;
    }
}
