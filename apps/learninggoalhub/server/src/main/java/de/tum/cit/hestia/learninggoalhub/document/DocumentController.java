package de.tum.cit.hestia.learninggoalhub.document;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public record DocumentResponse(Long id,
                                   Long courseId,
                                   String filename,
                                   String contentType,
                                   OffsetDateTime uploadedAt) {
        static DocumentResponse from(Document document) {
            return new DocumentResponse(
                    document.getId(),
                    document.getCourse().getId(),
                    document.getFilename(),
                    document.getContentType(),
                    document.getUploadedAt()
            );
        }
    }
}
