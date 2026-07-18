package de.tum.cit.hestia.learninggoalhub.extraction;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtractionRunRepository extends JpaRepository<ExtractionRun, Long> {

    List<ExtractionRun> findByCourseId(Long courseId);
}
