package de.tum.cit.hestia.learninggoalhub.document;

import de.tum.cit.hestia.learninggoalhub.course.Course;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentUploadService {

    private final DocumentRepository documentRepository;
    private final DocumentContentRepository documentContentRepository;
    private final DocumentSectionRepository documentSectionRepository;
    private final DocumentTitleService titleService;
    private final LanguageDetectionService languageDetectionService;

    public DocumentUploadService(DocumentRepository documentRepository,
                                 DocumentContentRepository documentContentRepository,
                                 DocumentSectionRepository documentSectionRepository,
                                 DocumentTitleService titleService,
                                 LanguageDetectionService languageDetectionService) {
        this.documentRepository = documentRepository;
        this.documentContentRepository = documentContentRepository;
        this.documentSectionRepository = documentSectionRepository;
        this.titleService = titleService;
        this.languageDetectionService = languageDetectionService;
    }

    @Transactional
    public Document persist(Course course, String filename, String contentType,
                            DocumentStructureService.ParsedDocument parsed, byte[] bytes) {
        String storedContentType = contentType != null ? contentType : "application/octet-stream";
        Document document = new Document(course, filename, storedContentType, parsed.rawText());
        document.setLanguage(languageDetectionService.detect(parsed.rawText()));
        document = documentRepository.save(document);
        document.setPageOffsets(parsed.pageOffsets());
        documentContentRepository.save(new DocumentContent(document, bytes));
        persistSections(document, parsed, bytes, contentType, filename);
        return document;
    }

    /**
     * Persists the document's structural sections. With deterministic bookmark sections we store
     * them as-is. With none (a slide deck / exercise without bookmarks) the document is one session;
     * we ask the vision model to name it from its first pages and store that single section, falling
     * back to leaving it section-less (the extraction step then titles the session by filename).
     */
    private void persistSections(Document document, DocumentStructureService.ParsedDocument parsed,
                                 byte[] bytes, String contentType, String filename) {
        List<DocumentStructureService.SectionSpan> sections = parsed.sections();
        if (!sections.isEmpty()) {
            for (int i = 0; i < sections.size(); i++) {
                DocumentStructureService.SectionSpan s = sections.get(i);
                documentSectionRepository.save(
                        new DocumentSection(document, i, s.title(), s.startOffset(), s.endOffset()));
            }
            return;
        }
        String title = titleService.deriveTitle(bytes, contentType, filename);
        if (title != null) {
            int end = parsed.rawText() == null ? 0 : parsed.rawText().length();
            documentSectionRepository.save(new DocumentSection(document, 0, title, 0, end));
        }
    }
}
