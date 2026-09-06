package app.storage;
import app.shared.Access;

import app.error.ApiException;
import app.exam.Exam;
import app.exam.ExamRepository;
import app.parse.DocxToPdfConverter;
import app.parse.PdfPageCounter;
import app.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@Tag(name = "Files", description = "Exam source-file upload and signed-URL object download.")
public class FileController {

    private static final String PDF_BUCKET = "exam-pdfs";

    private final StorageService storage;
    private final ExamRepository examRepository;
    private final Access access;
    private final SignedUrls signedUrls;
    private final DocxToPdfConverter docxConverter;
    private final PdfPageCounter pageCounter;

    public FileController(StorageService storage, ExamRepository examRepository,
                          Access access, SignedUrls signedUrls,
                          DocxToPdfConverter docxConverter, PdfPageCounter pageCounter) {
        this.storage = storage;
        this.examRepository = examRepository;
        this.access = access;
        this.signedUrls = signedUrls;
        this.docxConverter = docxConverter;
        this.pageCounter = pageCounter;
    }

    /**
     * Upload the exam source file; stores a PDF at exam-pdfs/{userId}/{examId}.pdf and records
     * source_file_url. Accepts a PDF directly, or a Word .docx which is converted to PDF here so
     * the rest of the (PDF-only) parse pipeline is unchanged. Detected by magic bytes: %PDF for
     * PDF, PK\x03\x04 (ZIP) for .docx. Legacy .doc and other formats are rejected.
     */
    @Operation(
        summary = "Upload an exam's source document",
        description = """
            `multipart/form-data` with a single `file` part. Accepts a PDF, or a Word \
            `.docx` which is converted to PDF server-side so the PDF-only parse pipeline is \
            unchanged. The format is detected by magic bytes, not by filename or content \
            type — legacy `.doc` and everything else is rejected. Spring caps the request at \
            12 MB.

            Stores the PDF, records it on the exam, and returns `{ storage_path }` — pass \
            that straight to `POST /api/parse-exam-pdf`.""")
    @ApiResponse(responseCode = "400",
        description = "Not a PDF or `.docx`, or the Word document could not be converted.")
    @ApiResponse(responseCode = "403", description = "The exam belongs to another owner.")
    @ApiResponse(responseCode = "404", description = "No such exam, or `examId` is not a valid UUID.")
    @ApiResponse(responseCode = "503",
        description = "Word conversion timed out or the converter pool is saturated. Retryable.")
    // `consumes` is what makes the spec advertise multipart/form-data; without it
    // springdoc documents the (correct) file schema under application/json.
    @PostMapping(value = "/exams/{examId}/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadPdf(@PathVariable String examId,
                                         @RequestParam("file")
                                         @Parameter(description = "The exam PDF or Word .docx.")
                                         MultipartFile file,
                                         @CurrentUser String userId) throws IOException {
        Exam exam = access.requireExam(Access.id(examId), userId);
        byte[] bytes = file.getBytes();
        byte[] pdfBytes;
        // ZIP is matched first by its exact byte-0 magic (PK\x03\x04); the PDF check
        // is a looser first-1KB scan, so testing ZIP first avoids misrouting a zip
        // that merely contains "%PDF-" into the passthrough branch.
        if (isZip(bytes)) {
            // Very likely a .docx (Office Open XML is a ZIP). Convert; a non-Word zip
            // will fail the conversion and surface the message below.
            try {
                pdfBytes = docxConverter.toPdf(bytes);
            } catch (TimeoutException e) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "This Word document took too long to convert. Please simplify it or upload a PDF instead.");
            } catch (RejectedExecutionException e) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The server is busy converting documents right now. Please try again in a moment.");
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "We couldn't convert this Word document. Please upload a .docx or a PDF and try again.");
            }
        } else if (isPdf(bytes)) {
            pdfBytes = bytes;
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Unsupported file. Please upload a PDF or a Word .docx file.");
        }
        String path = userId + "/" + examId + ".pdf";
        storage.store(PDF_BUCKET, path, pdfBytes);
        exam.setSourceFileUrl(path);
        // Document metadata used by the frontend for a page-count-based parsing
        // time estimate; cheap PDFBox call, null if the count can't be read.
        exam.setPageCount(pageCounter.pageCount(pdfBytes));
        examRepository.save(exam);
        return Map.of("storage_path", path);
    }

    private static boolean isPdf(byte[] b) {
        // Mirror the frontend precheck (client/src/lib/parsing/pdf-precheck.ts):
        // accept "%PDF-" anywhere in the first 1 KB, not just at byte 0. The spec
        // tolerates leading bytes (BOM / whitespace) before the header, and real
        // PDFs have them; a strict byte-0 check rejected files the precheck passed.
        // ISO-8859-1 maps each byte 1:1 to a char, matching the client's latin1 decode.
        int n = Math.min(b.length, 1024);
        return new String(b, 0, n, StandardCharsets.ISO_8859_1).contains("%PDF-");
    }

    private static boolean isZip(byte[] b) {
        return b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 0x03 && b[3] == 0x04;
    }

    /**
     * Serve a stored object for a valid signed URL. PUBLIC route (see SecurityConfig)
     * — authorization is the HMAC signature + expiry, so plain &lt;img src&gt; works.
     */
    @Operation(
        summary = "Download a stored object via a signed URL",
        description = """
            Serves a stored PDF or figure. **Unauthenticated by design** — authorization is \
            the HMAC signature plus expiry in the query string, so the URL works in a plain \
            `<img src>` or a new browser tab. Do not send a bearer token here; get a ready-made \
            URL from `GET /api/figures/{id}/signed-url` instead of assembling one by hand.

            `path` is a wildcard and may contain slashes (objects are keyed \
            `{userId}/{examId}...`). The response `Content-Type` is inferred from the file \
            extension.""")
    @SecurityRequirements
    @ApiResponse(responseCode = "200", description = "The object's bytes.",
        content = @Content(schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "403", description = "Signature invalid or expired.", content = @Content)
    @ApiResponse(responseCode = "404", description = "No such object in the bucket.", content = @Content)
    @GetMapping("/files/{bucket}/{*path}")
    public ResponseEntity<byte[]> content(
        @PathVariable @Parameter(description = "Storage bucket, e.g. `exam-pdfs` or `exam-figures`.") String bucket,
        @PathVariable @Parameter(description = "Object key within the bucket; may contain slashes.") String path,
        @RequestParam @Parameter(description = "Expiry as a Unix epoch second.") long exp,
        @RequestParam @Parameter(description = "HMAC signature over bucket, path, and expiry.") String sig) {
        String clean = path.startsWith("/") ? path.substring(1) : path;
        if (!signedUrls.verify(bucket, clean, exp, sig)) {
            return ResponseEntity.status(403).build();
        }
        byte[] bytes = storage.download(bucket, clean);
        if (bytes == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(contentType(clean)).body(bytes);
    }

    private static MediaType contentType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
