package de.tum.cit.hestia.learninggoalhub.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void extractsTextFromPdf() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/parser/sample.pdf")) {
            String text = parser.parse(in);

            assertThat(text)
                    .contains("Learning goal: Students can explain Tika parsing.")
                    .contains("This is a fixture used by DocumentParserTest.");
        }
    }
}
