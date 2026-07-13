package de.tum.cit.hestia.learninggoalhub.document;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByCourseId(Long courseId);
}
