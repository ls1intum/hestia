package de.tum.cit.hestia.learninggoalhub.document;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentSectionRepository extends JpaRepository<DocumentSection, Long> {

    List<DocumentSection> findByDocumentIdOrderByOrdinal(Long documentId);
}
