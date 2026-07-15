package app.shared;

import app.error.ApiException;
import app.exam.Exam;
import app.section.Section;
import app.exam.ExamRepository;
import app.section.SectionRepository;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Ownership/loading helpers shared by the controllers and services. Single-user
 * today (every row owned by {@code DefaultUser.ID}), but the owner check is
 * kept correct so real auth slots in later.
 */
@Component
public class Access {

    private final ExamRepository examRepository;
    private final SectionRepository sectionRepository;

    public Access(ExamRepository examRepository, SectionRepository sectionRepository) {
        this.examRepository = examRepository;
        this.sectionRepository = sectionRepository;
    }

    /** Load an exam and verify the caller owns it; 404 if missing, 403 if not owner. */
    public Exam requireExam(UUID examId, String userId) {
        Exam exam = examRepository.findById(examId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam not found"));
        if (!exam.getOwnerId().toString().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        return exam;
    }

    /**
     * Load an exam-scoped child row (task, section, block, ...) and verify the
     * caller owns its exam; 404 if missing, 403 if not owner. Centralized so an
     * endpoint can't accidentally skip the ownership hop.
     */
    public <T> T requireOwnedChild(
        JpaRepository<T, UUID> repository, String id, String userId,
        Function<T, UUID> examIdOf, String label
    ) {
        T child = repository.findById(id(id))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, label + " not found"));
        requireExam(examIdOf.apply(child), userId);
        return child;
    }

    /**
     * Assert that a section exists and belongs to the given exam — guards
     * cross-exam reassignment when a PATCH moves a task/block between sections.
     */
    public void requireSectionInExam(UUID sectionId, UUID examId) {
        Section section = sectionRepository.findById(sectionId)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Unknown section"));
        if (!section.getExamId().equals(examId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Section belongs to a different exam");
        }
    }

    /** Parse a path id, mapping a malformed value to 404 rather than 500. */
    public static UUID id(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
        }
    }
}
