package de.tum.cit.hestia.learninggoalhub.extraction;

/** Shared system instruction for calls that generate learner-facing text. */
public final class LanguagePrompt {

    private LanguagePrompt() {
    }

    public static String systemInstruction(String languageName) {
        return "Language invariant: every GENERATED field (text, shortLabel) must be written in "
                + languageName + ", regardless of the language of these instructions or of the material. "
                + "Verbatim quotes copied from the document and any other quoted material must stay "
                + "untouched in the document's own language. Do not translate sourceSnippet or quoted text. "
                + OutcomeWording.instruction() + " "
                + "JSON invariant: a backslash inside a string value must be written as \\\\. Material full "
                + "of mathematical notation invites sequences such as \\( or \\frac; write them as \\\\( and "
                + "\\\\frac, or leave the notation out, but never emit a bare backslash.";
    }

    public static String retrySystemInstruction(String languageName) {
        return systemInstruction(languageName)
                + " This is a language-correction retry: check every generated field before responding "
                + "and regenerate the complete response in " + languageName + "; do not repeat a response "
                + "in any other language.";
    }
}
