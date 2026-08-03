package de.tum.cit.hestia.learninggoalhub.extraction;

/** Strength of the match between an extracted source snippet and document text. */
public enum SourceMatchQuality {
    EXACT_IN_SESSION,
    EXACT_IN_DOCUMENT,
    NORMALIZED,
    FRAGMENT,
    NONE
}
