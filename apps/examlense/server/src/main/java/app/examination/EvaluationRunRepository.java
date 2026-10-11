package app.examination;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface EvaluationRunRepository extends JpaRepository<EvaluationRun, UUID> {
    Optional<EvaluationRun> findByExamId(UUID examId);

    @Transactional
    void deleteByExamId(UUID examId);
}
