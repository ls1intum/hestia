package de.tum.cit.hestia.learninggoalhub.document;

import java.io.IOException;
import java.io.InputStream;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;

@Service
public class DocumentParser {

    private final Tika tika;

    public DocumentParser() {
        this.tika = new Tika();
        this.tika.setMaxStringLength(-1);
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
