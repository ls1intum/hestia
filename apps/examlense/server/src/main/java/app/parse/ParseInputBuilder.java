package app.parse;

import app.ai.AiProvider;
import app.ai.ParserStrategy;
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

    private final PdfRasterizer rasterizer;
    private final ParseProgress progress;

    ParseInputBuilder(PdfRasterizer rasterizer, ParseProgress progress) {
        this.rasterizer = rasterizer;
        this.progress = progress;
    }

    AiProvider.UserContent build(ParserStrategy.PdfMode mode, UUID examId, byte[] bytes) {
        return switch (mode) {
            case RASTERIZE -> rasterized(examId, bytes);
            case PDF_DIRECT -> pdfDirect(bytes);
        };
    }

    private AiProvider.UserContent rasterized(UUID examId, byte[] bytes) {
        List<byte[]> pages;
        long tRaster = System.nanoTime();
        try {
            progress.setPhase(examId, "rasterizing");
            pages = rasterizer.rasterize(bytes, RASTERIZE_DPI);
            log.info("parse-exam-pdf[{}] timing step=rasterize took={}ms pages={}",
                examId, msSince(tRaster), pages.size());
        } catch (Exception e) {
            log.error("parse-exam-pdf[{}] rasterize failed", examId, e);
            throw new InputException(ParseErrorMessages.PDF_UNREADABLE, e);
        }
        List<AiProvider.ContentPart> parts = new ArrayList<>();
        parts.add(new AiProvider.TextPart(
            "Extract the exam from the following page images, in reading order."));
        for (byte[] png : pages) {
            String b64 = Base64.getEncoder().encodeToString(png);
            parts.add(new AiProvider.ImageUrlPart("data:image/png;base64," + b64));
        }
        return new AiProvider.MultipartContent(parts);
    }

    private AiProvider.UserContent pdfDirect(byte[] bytes) {
        String b64 = Base64.getEncoder().encodeToString(bytes);
        return new AiProvider.MultipartContent(List.of(
            new AiProvider.TextPart("Extract the exam."),
            new AiProvider.FilePart("exam.pdf", b64, "application/pdf")
        ));
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
