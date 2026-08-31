package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.Locale;

/** Shared wording contract for learner-facing outcome text and its compact label. */
final class OutcomeWording {

    private OutcomeWording() {
    }

    static String instruction() {
        return "Outcome wording invariant: text and shortLabel must express the same single action but "
                + "must never be identical. text must be an expanded action-noun phrase with meaningful "
                + "scope, context, method, or purpose beyond shortLabel; never use direct address or a "
                + "personal/imperative sentence. Use exactly one primary assessable learner action: "
                + "coordinated objects under that action are allowed, but coordinated action verbs joined "
                + "by commas, conjunctions, or slashes are not. Reuse the same action in shortLabel; a "
                + "topic-only label is invalid. In English, begin text with a gerund such as 'Applying', "
                + "'Analysing', or 'Understanding'. In German, begin text with whichever action-noun "
                + "form is natural for that verb: the nominalized infinitive ('Anwenden', 'Analysieren') "
                + "or the corresponding action noun ('Anwendung', 'Analyse', 'Konstruktion'). Do not force "
                + "a verb into a form you would not write unprompted. For every other output language, use "
                + "its natural action-noun or gerund equivalent. shortLabel remains a compact 2-6 word "
                + "action label in the natural word order of the output language.";
    }

    static void validate(String text, String shortLabel, String languageName, String subject) {
        validateTextAndLabelPresence(text, shortLabel, subject);
        String normalizedText = normalized(text);
        String normalizedLabel = normalized(shortLabel);
        if (normalizedText.equals(normalizedLabel)) {
            throw new IllegalArgumentException(subject + " text and shortLabel must not be identical");
        }
        if (normalizedText.length() < normalizedLabel.length() + 5) {
            throw new IllegalArgumentException(subject + " text must add meaningful detail beyond shortLabel");
        }
        validateTextGrammar(text, languageName, subject);
    }

    /**
     * Audited taxonomy output has already passed a distinct-label skeleton stage. If the audit model
     * collapses a pair back to the same learner-facing wording, keep it as a presentation fallback
     * instead of losing the complete source partition. All other wording rules still apply.
     */
    static void validateAudited(String text, String shortLabel, String languageName, String subject) {
        validateTextAndLabelPresence(text, shortLabel, subject);
        if (!normalized(text).equals(normalized(shortLabel))
                && normalized(text).length() < normalized(shortLabel).length() + 5) {
            throw new IllegalArgumentException(subject + " text must add meaningful detail beyond shortLabel");
        }
        // The audited plan is checked by an independent semantic reviewer for an assessable learner
        // action. Do not apply the first-word suffix heuristic here: valid action-noun constructions
        // differ across languages (for example German "Analyse ..." as well as "Analysieren ...").
    }

    private static void validateTextAndLabelPresence(String text, String shortLabel, String subject) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(subject + " must have non-blank text");
        }
        if (shortLabel == null || shortLabel.isBlank()) {
            throw new IllegalArgumentException(subject + " must have a non-blank shortLabel");
        }
    }

    private static void validateTextGrammar(String text, String languageName, String subject) {
        String language = languageName == null ? "" : languageName.strip().toLowerCase(Locale.ROOT);
        String firstWord = text.strip().split("\\s+", 2)[0].replaceAll("^[^\\p{L}]+|[^\\p{L}-]+$", "");
        String lowerFirstWord = firstWord.toLowerCase(Locale.ROOT);
        if (language.equals("english")) {
            if (!lowerFirstWord.endsWith("ing")) {
                throw new IllegalArgumentException(subject + " English text must begin with a gerund");
            }
            if (text.matches("(?is).*\\byou\\b.*")) {
                throw new IllegalArgumentException(subject + " text must not address the learner directly");
            }
        } else if (language.equals("german")) {
            if (!isGermanActionNoun(lowerFirstWord)) {
                throw new IllegalArgumentException(subject + " German text must begin with an action noun: "
                        + "a nominalized infinitive such as 'Anwenden', or the matching noun such as "
                        + "'Anwendung', 'Analyse' or 'Konstruktion'");
            }
            if (text.matches("(?s).*\\bSie\\b.*")) {
                throw new IllegalArgumentException(subject + " text must not address the learner directly");
            }
        }
    }

    /**
     * German has two idiomatic ways to open a learning outcome, and demanding only the nominalized
     * infinitive made the model DERIVE one on demand for verbs whose natural form is the noun. That
     * derivation is where the generator's German breaks: two separate runs opened a terminal skill
     * with "Konstruktieren", a blend of "Konstruktion" and "-ieren" that appears nowhere in the
     * corpus (which uses "konstruieren" 26 times). Accepting the noun removes the forcing function.
     * Finite and imperative forms — "Modelliert", "Wende", "Entwickle" — still fail, which is what
     * the rule is actually for.
     *
     * <p>{@link #validateAudited} already assumed both forms were valid; this brings the strict path
     * in line with that.
     */
    private static boolean isGermanActionNoun(String lowerFirstWord) {
        return lowerFirstWord.endsWith("en")     // Anwenden, Analysieren, Verstehen
                || lowerFirstWord.endsWith("ung")  // Anwendung, Berechnung, Modellierung
                || lowerFirstWord.endsWith("ion")  // Konstruktion, Klassifikation, Interpretation
                || lowerFirstWord.endsWith("yse")  // Analyse
                || lowerFirstWord.endsWith("ese"); // Synthese
    }

    private static String normalized(String value) {
        return value.strip()
                .replaceAll("[.!?]+$", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
