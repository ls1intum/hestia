package app.section;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SectionFigureRepository extends JpaRepository<SectionFigure, UUID> {
    List<SectionFigure> findByBlockIdOrderByPositionAsc(UUID blockId);

    @Query("""
        select f.storagePath from SectionFigure f, SectionBlock b
        where f.blockId = b.id and b.examId = :examId and b.sectionId = :sectionId
        """)
    List<String> findStoragePathsBySection(@Param("examId") UUID examId,
                                           @Param("sectionId") UUID sectionId);

    @Query("""
        select f.storagePath from SectionFigure f, SectionBlock b
        where f.blockId = b.id and b.examId = :examId
        """)
    List<String> findStoragePathsByExamId(@Param("examId") UUID examId);
}
