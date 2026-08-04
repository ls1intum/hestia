package de.tum.cit.hestia.learninggoalhub.document;

/** A highlight rectangle in unrotated PDF user space, relative to the page's CropBox. */
public record HighlightRect(double x, double y, double width, double height) {
}
