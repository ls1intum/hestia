package de.tum.cit.hestia.learninggoalhub.document;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Language-code helpers shared by extraction, the Topic tree and exam prompt orchestration. */
public final class LanguageUtils {

    private LanguageUtils() {
    }

    public static String englishName(String code) {
        if (code == null || code.isBlank()) {
            return "English";
        }
        String name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH);
        return name.isBlank() ? "English" : name;
    }

    /**
     * The language generated text is written in: the course override, else the document's own
     * language, else the course's dominant language, else English.
     */
    public static String resolveLanguage(Course course, String documentLanguage, String dominantLanguage) {
        if (course != null && course.getOutputLanguage() != null) {
            return course.getOutputLanguage();
        }
        if (documentLanguage != null && !documentLanguage.isBlank()) {
            return documentLanguage;
        }
        if (dominantLanguage != null && !dominantLanguage.isBlank()) {
            return dominantLanguage;
        }
        return "en";
    }

    /** The English name of the language text generated for the course as a whole is written in. */
    public static String courseLanguageName(Course course, List<Document> documents) {
        return englishName(resolveLanguage(course, null, dominantLanguage(documents)));
    }

    /** The language with the most raw text among the documents, or null when none has a language. */
    public static String dominantLanguage(List<Document> documents) {
        Map<String, Long> weights = new LinkedHashMap<>();
        for (Document document : documents) {
            String language = document.getLanguage();
            if (language == null || language.isBlank()) {
                continue;
            }
            String text = document.getRawText();
            long weight = text == null ? 0 : text.length();
            if (weight > 0) {
                weights.merge(language, weight, Long::sum);
            }
        }
        String dominant = null;
        long highestWeight = -1;
        for (Map.Entry<String, Long> entry : weights.entrySet()) {
            if (entry.getValue() > highestWeight) {
                dominant = entry.getKey();
                highestWeight = entry.getValue();
            }
        }
        return dominant;
    }
}
