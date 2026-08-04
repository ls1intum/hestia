package de.tum.cit.hestia.learninggoalhub.document;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PageDescriptionRepository extends JpaRepository<PageDescription, PageDescriptionId> {

    @Query("select p from PageDescription p where p.document.id = :documentId order by p.id.page")
    List<PageDescription> findByDocumentId(@Param("documentId") Long documentId);

    @Query("select p from PageDescription p where p.document.id in :documentIds")
    List<PageDescription> findByDocumentIdIn(@Param("documentIds") Collection<Long> documentIds);
}
