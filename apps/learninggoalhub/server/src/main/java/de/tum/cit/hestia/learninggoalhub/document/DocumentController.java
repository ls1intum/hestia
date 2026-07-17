package de.tum.cit.hestia.learninggoalhub.document;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
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
    private final DocumentSectionRepository documentSectionRepository;
    private final DocumentStructureService structureService;
    private final DocumentTitleService titleService;

    public DocumentController(CourseRepository courseRepository,
                              DocumentRepository documentRepository,
                              DocumentSectionRepository documentSectionRepository,
                              DocumentStructureService structureService,
                              DocumentTitleService titleService) {
        this.courseRepository = courseRepository;
        this.documentRepository = documentRepository;
        this.documentSectionRepository = documentSectionRepository;
        this.structureService = structureService;
        this.titleService = titleService;
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
            Document saved = documentRepository.save(new Document(
                    course,
                    file.getOriginalFilename(),
                    file.getContentType() != null ? file.getContentType() : "application/octet-stream",
                    parsed.rawText()
            ));
            persistSections(saved, parsed, bytes, file);
            responses.add(DocumentResponse.from(saved));
        }
        return responses;
    }

    /**
     * Persists the document's structural sections. With deterministic bookmark sections we store
     * them as-is. With none (a slide deck / exercise without bookmarks) the document is one session;
     * we ask the vision model to name it from its first pages and store that single section, falling
     * back to leaving it section-less (the extraction step then titles the session by filename).
     */
    private void persistSections(Document document, DocumentStructureService.ParsedDocument parsed,
                                 byte[] bytes, MultipartFile file) {
        List<DocumentStructureService.SectionSpan> sections = parsed.sections();
        if (!sections.isEmpty()) {
            for (int i = 0; i < sections.size(); i++) {
                DocumentStructureService.SectionSpan s = sections.get(i);
                documentSectionRepository.save(
                        new DocumentSection(document, i, s.title(), s.startOffset(), s.endOffset()));
            }
            return;
        }
        String title = titleService.deriveTitle(bytes, file.getContentType(), file.getOriginalFilename());
        if (title != null) {
            int end = parsed.rawText() == null ? 0 : parsed.rawText().length();
            documentSectionRepository.save(new DocumentSection(document, 0, title, 0, end));
        }
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
