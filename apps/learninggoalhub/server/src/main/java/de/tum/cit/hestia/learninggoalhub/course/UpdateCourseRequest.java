package de.tum.cit.hestia.learninggoalhub.course;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A partial update. {@code outputLanguage} needs to tell "left out" from "null": null switches
 * back to auto-detection, while a body that only renames the course must not.
 */
public class UpdateCourseRequest {

    private String name;
    private String outputLanguage;
    private boolean outputLanguageSet;
    private Boolean figuresEnabled;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Schema(nullable = true)
    public String getOutputLanguage() {
        return outputLanguage;
    }

    public void setOutputLanguage(String outputLanguage) {
        this.outputLanguage = outputLanguage;
        this.outputLanguageSet = true;
    }

    public Boolean getFiguresEnabled() {
        return figuresEnabled;
    }

    public void setFiguresEnabled(Boolean figuresEnabled) {
        this.figuresEnabled = figuresEnabled;
    }

    boolean hasOutputLanguage() {
        return outputLanguageSet;
    }
}
