package de.tum.cit.hestia.learninggoalhub.document;

import java.util.Collection;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentContentRepository extends JpaRepository<DocumentContent, Long> {

    @Query("select c.documentId from DocumentContent c where c.documentId in :documentIds")
    Set<Long> findExistingDocumentIds(@Param("documentIds") Collection<Long> documentIds);
}
