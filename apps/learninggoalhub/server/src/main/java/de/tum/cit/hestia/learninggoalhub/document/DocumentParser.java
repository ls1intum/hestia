package de.tum.cit.hestia.learninggoalhub.document;

import java.io.IOException;
import java.io.InputStream;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;

@Service
public class DocumentParser {

    static final int MAX_STRING_LENGTH = 10_000_000;

    private final Tika tika;

    public DocumentParser() {
        this.tika = new Tika();
        // Bounded, so a text-heavy file cannot grow without limit in memory; uploads above the
        // configured hestia.upload.max-text-chars are rejected after parsing anyway.
        this.tika.setMaxStringLength(MAX_STRING_LENGTH);
    }

    public String parse(InputStream input) {
        try {
            return tika.parseToString(input);
        } catch (IOException | TikaException e) {
            throw new DocumentParseException("Failed to extract text from document", e);
        }
    }

    public static class DocumentParseException extends RuntimeException {
        public DocumentParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
