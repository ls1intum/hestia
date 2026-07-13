package app.persistence.repository;

import app.persistence.entity.SectionBlock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SectionBlockRepository extends JpaRepository<SectionBlock, UUID> {
    List<SectionBlock> findByExamIdOrderByPositionAsc(UUID examId);
    List<SectionBlock> findBySectionIdOrderByPositionAsc(UUID sectionId);

    @Transactional
    void deleteByExamIdAndSectionId(UUID examId, UUID sectionId);

    @Modifying
    @Transactional
    @Query("update SectionBlock b set b.position = b.position + 1 "
        + "where b.sectionId = :sectionId and b.position >= :fromPos")
    int shiftBlocksInSection(@Param("sectionId") UUID sectionId, @Param("fromPos") int fromPos);
}
