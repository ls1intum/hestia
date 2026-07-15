package app.storage;
import app.shared.Access;

import app.error.ApiException;
import app.exam.Exam;
import app.exam.ExamRepository;
import app.security.CurrentUser;
import java.io.IOException;
import java.util.Map;
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
public class FileController {

    private static final String PDF_BUCKET = "exam-pdfs";

    private final StorageService storage;
    private final ExamRepository examRepository;
    private final Access access;
    private final SignedUrls signedUrls;

    public FileController(StorageService storage, ExamRepository examRepository,
                          Access access, SignedUrls signedUrls) {
        this.storage = storage;
        this.examRepository = examRepository;
        this.access = access;
        this.signedUrls = signedUrls;
    }

    /** Upload the exam PDF; stores at exam-pdfs/{userId}/{examId}.pdf and records source_file_url. */
    @PostMapping("/exams/{examId}/pdf")
    public Map<String, Object> uploadPdf(@PathVariable String examId,
                                         @RequestParam("file") MultipartFile file,
                                         @CurrentUser String userId) throws IOException {
        Exam exam = access.requireExam(Access.id(examId), userId);
        byte[] bytes = file.getBytes();
        // Magic-bytes check: fail here with a clear message instead of later
        // in the parse pipeline when PDFBox chokes on a non-PDF.
        if (bytes.length < 4 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F') {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File is not a PDF");
        }
        String path = userId + "/" + examId + ".pdf";
        storage.store(PDF_BUCKET, path, bytes);
        exam.setSourceFileUrl(path);
        examRepository.save(exam);
        return Map.of("storage_path", path);
    }

    /**
     * Serve a stored object for a valid signed URL. PUBLIC route (see SecurityConfig)
     * — authorization is the HMAC signature + expiry, so plain &lt;img src&gt; works.
     */
    @GetMapping("/files/{bucket}/{*path}")
    public ResponseEntity<byte[]> content(@PathVariable String bucket, @PathVariable String path,
                                          @RequestParam long exp, @RequestParam String sig) {
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
