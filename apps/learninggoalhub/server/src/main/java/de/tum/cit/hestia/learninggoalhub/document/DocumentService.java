package de.tum.cit.hestia.learninggoalhub.document;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.server.ResponseStatusException;

/** Lists, stores, serves and updates the documents uploaded to a course. */
@Service
public class DocumentService {

    private final CourseRepository courseRepository;
    private final DocumentRepository documentRepository;
    private final DocumentContentRepository documentContentRepository;
    private final DocumentSectionRepository documentSectionRepository;
    private final DocumentTitleService titleService;
    private final LanguageDetectionService languageDetectionService;
    private final TransactionOperations transactions;

    public DocumentService(CourseRepository courseRepository,
                           DocumentRepository documentRepository,
                           DocumentContentRepository documentContentRepository,
                           DocumentSectionRepository documentSectionRepository,
                           DocumentTitleService titleService,
                           LanguageDetectionService languageDetectionService,
                           TransactionOperations transactions) {
        this.courseRepository = courseRepository;
        this.documentRepository = documentRepository;
        this.documentContentRepository = documentContentRepository;
        this.documentSectionRepository = documentSectionRepository;
        this.titleService = titleService;
        this.languageDetectionService = languageDetectionService;
        this.transactions = transactions;
    }

    /** The course's documents in course order. */
    @Transactional(readOnly = true)
    public List<Document> list(Long courseId) {
        courseRepository.requireExists(courseId);
        return documentRepository.findByCourseId(courseId).stream()
                .sorted(DocumentOrder.comparator())
                .toList();
    }

    /** The course documents are uploaded to, or a 404. */
    public Course course(Long courseId) {
        return courseRepository.findOrThrow(courseId);
    }

    /** A document with its stored bytes, or a 404 when either is missing or the document is in another course. */
    @Transactional(readOnly = true)
    public StoredDocument content(Long courseId, Long documentId) {
        Document document = findInCourse(courseId, documentId);
        DocumentContent content = documentContentRepository.findById(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Document content not found: " + documentId));
        return new StoredDocument(document, content.getBytes());
    }

    /**
     * Renames a document for display and/or changes its kind. The filename is immutable provenance
     * (goal sources and the CSV export cite it), so only the display name changes; an explicit null
     * clears it back to the filename. A field left out of the body is left unchanged, and a null kind
     * is ignored.
     */
    @Transactional
    public Document update(Long courseId, Long documentId, UpdateDocumentRequest request) {
        Document document = findInCourse(courseId, documentId);
        if (request.hasDisplayName()) {
            String displayName = request.getDisplayName() == null ? null : request.getDisplayName().trim();
            if (displayName != null && displayName.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName must not be blank");
            }
            document.setDisplayName(displayName);
        }
        if (request.getKind() != null) {
            document.setKind(request.getKind());
        }
        return documentRepository.save(document);
    }

    private Document findInCourse(Long courseId, Long documentId) {
        return documentRepository.findById(documentId)
                .filter(d -> d.getCourse().getId().equals(courseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + documentId));
    }

    /**
     * Stores an uploaded document with its content and sections. A document without bookmark
     * sections is one session; the vision model names it from its first pages before the write
     * transaction opens, so no connection is held while the model runs. Without a title the
     * document stays section-less and the extraction step titles the session by filename.
     */
    public Document persist(Course course, String filename, String contentType,
                            DocumentKind kind, DocumentStructureService.ParsedDocument parsed, byte[] bytes) {
        String title = parsed.sections().isEmpty()
                ? titleService.deriveTitle(bytes, contentType, filename)
                : null;
        String storedContentType = contentType != null ? contentType : "application/octet-stream";
        return transactions.execute(status -> {
            Document document = new Document(course, filename, storedContentType, parsed.rawText());
            document.setKind(kind);
            document.setLanguage(languageDetectionService.detect(parsed.rawText()));
            document = documentRepository.save(document);
            document.setPageOffsets(parsed.pageOffsets());
            documentContentRepository.save(new DocumentContent(document, bytes));
            persistSections(document, parsed, title);
            return document;
        });
    }

    /** Stores the bookmark sections as-is, or else the single titled section when there is a title. */
    private void persistSections(Document document, DocumentStructureService.ParsedDocument parsed, String title) {
        List<DocumentStructureService.SectionSpan> sections = parsed.sections();
        if (!sections.isEmpty()) {
            for (int i = 0; i < sections.size(); i++) {
                DocumentStructureService.SectionSpan s = sections.get(i);
                documentSectionRepository.save(
                        new DocumentSection(document, i, s.title(), s.startOffset(), s.endOffset(),
                                s.startPage(), s.endPage()));
            }
            return;
        }
        if (title != null) {
            int end = parsed.rawText() == null ? 0 : parsed.rawText().length();
            int pageCount = parsed.pageOffsets() == null ? 0 : parsed.pageOffsets().length - 1;
            documentSectionRepository.save(new DocumentSection(document, 0, title, 0, end,
                    pageCount > 0 ? 1 : null, pageCount > 0 ? pageCount : null));
        }
    }

    /** A document and the bytes that were uploaded for it. */
    public record StoredDocument(Document document, byte[] bytes) {
    }
}
