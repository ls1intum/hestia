package de.tum.cit.hestia.learninggoalhub.document;

import java.util.Locale;

/** Small language-code helper shared by extraction and exam prompt orchestration. */
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
}
