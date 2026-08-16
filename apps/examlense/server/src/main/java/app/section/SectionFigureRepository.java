package app.section;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SectionFigureRepository extends JpaRepository<SectionFigure, UUID> {
    List<SectionFigure> findByBlockIdOrderByPositionAsc(UUID blockId);
}
