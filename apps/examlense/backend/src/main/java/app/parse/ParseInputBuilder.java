package app.parse;

import app.ai.AiProvider;
import app.ai.ParserStrategy;
import app.persistence.repository.ExamRepository;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Assembles the LLM user content for one parse, per {@link ParserStrategy.PdfMode}:
 *   RASTERIZE  — every page rendered to a PNG data URL
 *   TEXT_ONLY  — PDFBox plain-text extraction (raw text persisted for audit)
 *   PDF_DIRECT — the PDF bytes inlined for providers with native document input
 *
 * Failures surface as {@link InputException} carrying a user-facing message.
 */
@Component
class ParseInputBuilder {

    /** Input assembly failed; the message is safe to show to the user. */
    static class InputException extends RuntimeException {
        InputException(String userMessage, Throwable cause) { super(userMessage, cause); }
    }

    private static final Logger log = LoggerFactory.getLogger(ParseInputBuilder.class);
    private static final float RASTERIZE_DPI = 150f;
    private static final int MAX_RAW_TEXT_CHARS = 500_000;

    private final PdfRasterizer rasterizer;
    private final PdfTextExtractor textExtractor;
    private final ExamRepository examRepository;
    private final ParseProgress progress;

    ParseInputBuilder(PdfRasterizer rasterizer, PdfTextExtractor textExtractor,
                      ExamRepository examRepository, ParseProgress progress) {
        this.rasterizer = rasterizer;
        this.textExtractor = textExtractor;
        this.examRepository = examRepository;
        this.progress = progress;
    }

    AiProvider.UserContent build(ParserStrategy.PdfMode mode, UUID examId, byte[] bytes, String languageHint) {
        String langSuffix = (languageHint != null && !languageHint.isBlank())
            ? " Language hint: " + languageHint + "."
            : "";
        return switch (mode) {
            case RASTERIZE -> rasterized(examId, bytes, langSuffix);
            case TEXT_ONLY -> extractedText(examId, bytes, langSuffix);
            case PDF_DIRECT -> pdfDirect(bytes, langSuffix);
        };
    }

    private AiProvider.UserContent rasterized(UUID examId, byte[] bytes, String langSuffix) {
        List<byte[]> pages;
        long tRaster = System.nanoTime();
        try {
            progress.setPhase(examId, "rasterizing");
            pages = rasterizer.rasterize(bytes, RASTERIZE_DPI);
            log.info("parse-exam-pdf[{}] timing step=rasterize took={}ms pages={}",
                examId, msSince(tRaster), pages.size());
        } catch (Exception e) {
            log.error("parse-exam-pdf[{}] rasterize failed", examId, e);
            throw new InputException("Could not render PDF pages for the selected parser model.", e);
        }
        List<AiProvider.ContentPart> parts = new ArrayList<>();
        parts.add(new AiProvider.TextPart(
            "Extract the exam from the following page images, in reading order." + langSuffix));
        for (byte[] png : pages) {
            String b64 = Base64.getEncoder().encodeToString(png);
            parts.add(new AiProvider.ImageUrlPart("data:image/png;base64," + b64));
        }
        return new AiProvider.MultipartContent(parts);
    }

    private AiProvider.UserContent extractedText(UUID examId, byte[] bytes, String langSuffix) {
        String text;
        long tExtract = System.nanoTime();
        try {
            progress.setPhase(examId, "extracting");
            text = textExtractor.extractText(bytes);
            log.info("parse-exam-pdf[{}] timing step=text-extract took={}ms chars={}",
                examId, msSince(tExtract), text.length());
        } catch (PdfTextExtractor.TextExtractionException e) {
            log.error("parse-exam-pdf[{}] text extraction failed", examId, e);
            throw new InputException(e.getMessage(), e);
        } catch (Exception e) {
            log.error("parse-exam-pdf[{}] text extraction failed", examId, e);
            throw new InputException("Could not extract text from this PDF.", e);
        }
        persistRawText(examId, text);
        return new AiProvider.MultipartContent(List.of(
            new AiProvider.TextPart(
                "You will receive the exam as PLAIN TEXT extracted from the PDF page-by-page."
                    + " Pages are delimited by `----- Page N -----` markers; use those to populate"
                    + " figures[].page_number and to reason about layout boundaries."
                    + " Line wraps are mechanical, not semantic — paragraphs may span lines and"
                    + " words may be hyphenated across line breaks; reconstruct sensibly."
                    + " Math, code, and tables may have lost typographic formatting; restore them"
                    + " with markdown ($..$ / $$..$$ for math, ```...``` for code) where appropriate."
                    + langSuffix
                    + "\n\n----- PDF TEXT -----\n" + text)
        ));
    }

    private AiProvider.UserContent pdfDirect(byte[] bytes, String langSuffix) {
        String b64 = Base64.getEncoder().encodeToString(bytes);
        return new AiProvider.MultipartContent(List.of(
            new AiProvider.TextPart("Extract the exam." + langSuffix),
            new AiProvider.FilePart("exam.pdf", b64, "application/pdf")
        ));
    }

    /**
     * Persist the raw extractor output for audit / debugging. Capped so a
     * massive scan-with-OCR-like-bulk doesn't blow up the row; best-effort.
     */
    private void persistRawText(UUID examId, String text) {
        String stored = text.length() > MAX_RAW_TEXT_CHARS
            ? text.substring(0, MAX_RAW_TEXT_CHARS) + "\n…truncated"
            : text;
        try {
            examRepository.updateParseRawText(examId, stored);
        } catch (Exception e) {
            log.warn("parse-exam-pdf[{}] failed to persist raw text: {}", examId, e.getMessage());
        }
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
