package app.parse;

import java.io.ByteArrayOutputStream;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocxToPdfConverterTest {

    private final DocxToPdfConverter converter = new DocxToPdfConverter();

    @Test
    void convertsDocxToPdf() throws Exception {
        WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
        pkg.getMainDocumentPart().addParagraphOfText("Question 1: What is 2 + 2?");
        ByteArrayOutputStream docx = new ByteArrayOutputStream();
        pkg.save(docx);

        byte[] pdf = converter.toPdf(docx.toByteArray());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void rejectsNonDocxInput() {
        assertThatThrownBy(() -> converter.toPdf("not a docx".getBytes()))
            .isInstanceOf(Exception.class);
    }
}
