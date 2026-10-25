package de.tum.cit.hestia.learninggoalhub.document;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/courses/{courseId}/documents")
public class DocumentController {

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final int PDF_SIGNATURE_WINDOW = 1024;

    private final DocumentService documentService;
    private final DocumentStructureService structureService;
    private final int maxPages;
    private final int maxTextChars;

    public DocumentController(DocumentService documentService,
                              DocumentStructureService structureService,
                              @Value("${hestia.upload.max-pages:2000}") int maxPages,
                              @Value("${hestia.upload.max-text-chars:3000000}") int maxTextChars) {
        this.documentService = documentService;
        this.structureService = structureService;
        this.maxPages = maxPages;
        this.maxTextChars = maxTextChars;
    }

    @GetMapping
    public List<DocumentResponse> list(@PathVariable Long courseId) {
        return documentService.list(courseId).stream()
                .map(DocumentResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<DocumentResponse> upload(@PathVariable Long courseId,
                                         @RequestParam("files") MultipartFile[] files,
                                         @Parameter(description = "Optional kind of every file in this upload: LECTURE or EXERCISE. "
                                                 + "Omit to upload without a kind, which keeps the title-based hierarchy level.")
                                         @RequestParam(required = false) DocumentKind kind) {
        Course course = documentService.course(courseId);

        // Every file is checked before any is parsed or stored, so a wrong file type rejects the whole
        // upload instead of leaving the files before it saved.
        List<byte[]> contents = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            contents.add(requirePdf(file));
        }

        List<DocumentResponse> responses = new ArrayList<>(files.length);
        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            byte[] bytes = contents.get(i);
            DocumentStructureService.ParsedDocument parsed;
            try {
                parsed = structureService.parse(bytes, file.getContentType(), file.getOriginalFilename());
            } catch (DocumentParser.DocumentParseException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Could not read uploaded file: " + file.getOriginalFilename(), e);
            }
            requireWithinLimits(file.getOriginalFilename(), parsed);
            Document saved = documentService.persist(
                    course, file.getOriginalFilename(), file.getContentType(), kind, parsed, bytes);
            responses.add(DocumentResponse.from(saved));
        }
        return responses;
    }

    /**
     * Only PDFs are accepted: the source viewer, quote highlights, bookmark sessions, figure
     * descriptions and page search all read the PDF itself. Checks the extension and the {@code %PDF-}
     * header, so a renamed file of another type is rejected too.
     */
    private static byte[] requirePdf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only PDF files can be uploaded: " + name);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file: " + name, e);
        }
        if (!hasPdfSignature(bytes)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Not a valid PDF file: " + name);
        }
        return bytes;
    }

    private void requireWithinLimits(String name, DocumentStructureService.ParsedDocument parsed) {
        int pages = parsed.pageOffsets() == null ? 0 : parsed.pageOffsets().length - 1;
        if (pages > maxPages) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    name + " has " + pages + " pages; at most " + maxPages + " are supported");
        }
        if (parsed.rawText().length() > maxTextChars) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    name + " contains more text than one document may (" + maxTextChars + " characters)");
        }
    }

    /** Readers accept the header anywhere in the first kilobyte, after stray leading bytes. */
    private static boolean hasPdfSignature(byte[] bytes) {
        int last = Math.min(bytes.length, PDF_SIGNATURE_WINDOW) - PDF_SIGNATURE.length;
        outer:
        for (int start = 0; start <= last; start++) {
            for (int i = 0; i < PDF_SIGNATURE.length; i++) {
                if (bytes[start + i] != PDF_SIGNATURE[i]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long courseId, @PathVariable Long documentId) {
        DocumentService.StoredDocument stored = documentService.content(courseId, documentId);
        Document document = stored.document();
        byte[] bytes = stored.bytes();
        boolean pdf = isPdf(document);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(bytes.length);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                contentDisposition(pdf ? "inline" : "attachment", document.getDisplayName() != null
                        ? document.getDisplayName() : document.getFilename()));
        MediaType mediaType = pdf ? MediaType.APPLICATION_PDF : mediaType(document.getContentType());
        headers.setContentType(mediaType);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    @PatchMapping("/{documentId}")
    public DocumentResponse update(@PathVariable Long courseId,
                                   @PathVariable Long documentId,
                                   @RequestBody UpdateDocumentRequest request) {
        return DocumentResponse.from(documentService.update(courseId, documentId, request));
    }

    private static boolean isPdf(Document document) {
        return (document.getContentType() != null
                && document.getContentType().toLowerCase(Locale.ROOT).contains("pdf"))
                || (document.getFilename() != null
                && document.getFilename().toLowerCase(Locale.ROOT).endsWith(".pdf"));
    }

    private static MediaType mediaType(String contentType) {
        if (contentType == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String contentDisposition(String type, String filename) {
        StringBuilder encoded = new StringBuilder();
        for (byte value : filename.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = value & 0xff;
            if (isRfc5987AttrChar(unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned >>> 4, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0x0f, 16)));
            }
        }
        return type + "; filename*=UTF-8''" + encoded;
    }

    private static boolean isRfc5987AttrChar(int value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '!' || value == '#' || value == '$' || value == '&'
                || value == '+' || value == '-' || value == '.' || value == '^'
                || value == '_' || value == '`' || value == '|' || value == '~';
    }

    public record DocumentResponse(Long id,
                                   Long courseId,
                                   String filename,
                                   String displayName,
                                   String contentType,
                                   @Schema(nullable = true) DocumentKind kind,
                                   OffsetDateTime uploadedAt) {
        static DocumentResponse from(Document document) {
            return new DocumentResponse(
                    document.getId(),
                    document.getCourse().getId(),
                    document.getFilename(),
                    document.getDisplayName(),
                    document.getContentType(),
                    document.getKind(),
                    document.getUploadedAt()
            );
        }
    }
}
