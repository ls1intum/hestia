package de.tum.cit.hestia.learninggoalhub.document;

import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;
import org.apache.tika.langdetect.optimaize.OptimaizeLangDetector;
import org.springframework.stereotype.Service;

/** Detects the language of parsed document text without involving a generative model. */
@Service
public class LanguageDetectionService {

    private final LanguageDetector detector;

    public LanguageDetectionService() {
        this.detector = new OptimaizeLangDetector().loadModels();
    }

    /**
     * @return an ISO 639-1 language code, or {@code null} when the text is blank or uncertain.
     */
    public synchronized String detect(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        LanguageResult result = detector.detect(text);
        if (result == null || !result.isReasonablyCertain()) {
            return null;
        }
        String language = result.getLanguage();
        return language == null || language.isBlank() ? null : language;
    }
}
