package de.tum.cit.hestia.learninggoalhub.document;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    private final CourseRepository courseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentContentRepository documentContentRepository;
    private final DocumentStructureService structureService;
    private final DocumentUploadService uploadService;

    public DocumentController(CourseRepository courseRepository,
                              DocumentRepository documentRepository,
                              DocumentContentRepository documentContentRepository,
                              DocumentStructureService structureService,
                              DocumentUploadService uploadService) {
        this.courseRepository = courseRepository;
        this.documentRepository = documentRepository;
        this.documentContentRepository = documentContentRepository;
        this.structureService = structureService;
        this.uploadService = uploadService;
    }

    @GetMapping
    public List<DocumentResponse> list(@PathVariable Long courseId) {
        if (!courseRepository.existsById(courseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId);
        }
        return documentRepository.findByCourseId(courseId).stream()
                .sorted(Comparator.comparing(Document::getId))
                .map(DocumentResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<DocumentResponse> upload(@PathVariable Long courseId,
                                         @RequestParam("files") MultipartFile[] files) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found: " + courseId));

        List<DocumentResponse> responses = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            byte[] bytes;
            DocumentStructureService.ParsedDocument parsed;
            try {
                bytes = file.getBytes();
                parsed = structureService.parse(bytes, file.getContentType(), file.getOriginalFilename());
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file: " + file.getOriginalFilename(), e);
            }
            Document saved = uploadService.persist(
                    course, file.getOriginalFilename(), file.getContentType(), parsed, bytes);
            responses.add(DocumentResponse.from(saved));
        }
        return responses;
    }

    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long courseId, @PathVariable Long documentId) {
        Document document = documentRepository.findById(documentId)
                .filter(d -> d.getCourse().getId().equals(courseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document not found: " + documentId));
        DocumentContent content = documentContentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document content not found: " + documentId));

        byte[] bytes = content.getBytes();
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

    /**
     * Renames a document for display. The filename is immutable provenance (goal sources and the
     * CSV export cite it), so only the display name changes; null clears it back to the filename.
     */
    @PatchMapping("/{documentId}")
    public DocumentResponse update(@PathVariable Long courseId,
                                   @PathVariable Long documentId,
                                   @RequestBody UpdateDocumentRequest request) {
        Document document = documentRepository.findById(documentId)
                .filter(d -> d.getCourse().getId().equals(courseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + documentId));
        String displayName = request.displayName() == null ? null : request.displayName().trim();
        if (displayName != null && displayName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName must not be blank");
        }
        document.setDisplayName(displayName);
        return DocumentResponse.from(documentRepository.save(document));
    }

    public record UpdateDocumentRequest(String displayName) {
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
                                   OffsetDateTime uploadedAt) {
        static DocumentResponse from(Document document) {
            return new DocumentResponse(
                    document.getId(),
                    document.getCourse().getId(),
                    document.getFilename(),
                    document.getDisplayName(),
                    document.getContentType(),
                    document.getUploadedAt()
            );
        }
    }
}
