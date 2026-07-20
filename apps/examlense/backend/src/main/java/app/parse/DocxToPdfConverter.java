package app.parse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.docx4j.Docx4J;
import org.docx4j.fonts.PhysicalFonts;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.stereotype.Component;

/**
 * Converts an uploaded Word {@code .docx} to PDF (pure-Java, via docx4j + Apache
 * FOP — no system dependency). Conversion happens at upload time so the rest of
 * the parse pipeline stays PDF-only; the resulting PDF is stored at the usual
 * {@code .pdf} key. Layout fidelity is "good enough" — complex Word layouts may
 * render imperfectly. Legacy {@code .doc} (OLE2) is not supported.
 *
 * The render (docx4j + FOP) is CPU/heap-heavy, so it runs on a small dedicated,
 * bounded pool with a timeout rather than inline on the caller's (HTTP request)
 * thread: this caps concurrent renders (heap protection) and stops a pathological
 * document from hanging a request thread indefinitely.
 */
@Component
public class DocxToPdfConverter {

    private static final long CONVERSION_TIMEOUT_SECONDS = 45;

    // Bounded on purpose: at most 2 concurrent renders, a short queue, then reject
    // fast (AbortPolicy → RejectedExecutionException). Daemon threads so the pool
    // never blocks JVM shutdown.
    private final ExecutorService pool = new ThreadPoolExecutor(
        1, 2, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4),
        r -> {
            Thread t = new Thread(r, "docx-convert");
            t.setDaemon(true);
            return t;
        },
        new ThreadPoolExecutor.AbortPolicy());

    static {
        // Disable system-font auto-discovery. docx4j's forked FOP can't safely parse
        // modern system fonts on our classpath: OpenType/CFF (.otf) fonts hit fontbox
        // 2.x's `CFFParser.parse(byte[])` (gone in the fontbox 3.x that PDFBox 3.x
        // ships → NoSuchMethodError), and some TrueType fonts trip a GPOS-table bug in
        // its typographic reader. A regex that matches no filename means zero physical
        // fonts are scanned, so FOP falls back to its built-in base-14 fonts. Fidelity
        // is irrelevant here — we only need readable text for the AI parser.
        PhysicalFonts.setRegex("__examlense_no_physical_fonts__");
    }

    /**
     * @param docx raw bytes of a .docx (Office Open XML / ZIP) file
     * @return PDF bytes
     * @throws TimeoutException if the render exceeds {@link #CONVERSION_TIMEOUT_SECONDS}
     * @throws java.util.concurrent.RejectedExecutionException if the pool is saturated
     * @throws Exception if the input isn't a valid .docx or conversion fails
     */
    public byte[] toPdf(byte[] docx) throws Exception {
        Future<byte[]> future = pool.submit(() -> convert(docx));
        try {
            return future.get(CONVERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Best-effort interrupt; FOP may not honor it, but the bounded pool
            // caps the blast radius of a stuck render.
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            // Surface the real conversion failure (bad .docx, etc.).
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private static byte[] convert(byte[] docx) throws Exception {
        WordprocessingMLPackage pkg =
            WordprocessingMLPackage.load(new ByteArrayInputStream(docx));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Docx4J.toPDF(pkg, out);
        return out.toByteArray();
    }
}
