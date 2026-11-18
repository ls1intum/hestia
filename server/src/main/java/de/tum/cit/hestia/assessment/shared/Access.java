package de.tum.cit.hestia.assessment.shared;

import de.tum.cit.hestia.assessment.error.ApiException;
import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.section.Section;
import de.tum.cit.hestia.assessment.section.SectionRepository;
import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.CurrentUser;
import de.tum.cit.hestia.core.spi.GoalDirectory;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Access checks shared by the controllers and services. An exam linked to a course is checked
 * against the caller's role in that course (read: any member, write: editor or owner); an exam
 * without a course belongs to its creator alone. Anything the caller may not see is a 404, so the
 * existence of foreign exams is not revealed; a member who is too small for a write gets a 403
 * from {@link CourseAccess}.
 */
@Component
public class Access {

    private final ExaminationRepository examRepository;
    private final SectionRepository sectionRepository;
    private final CourseAccess courseAccess;
    private final CurrentUser currentUser;
    private final GoalDirectory goalDirectory;

    public Access(ExaminationRepository examRepository, SectionRepository sectionRepository,
                  CourseAccess courseAccess, CurrentUser currentUser, GoalDirectory goalDirectory) {
        this.goalDirectory = goalDirectory;
        this.examRepository = examRepository;
        this.sectionRepository = sectionRepository;
        this.courseAccess = courseAccess;
        this.currentUser = currentUser;
    }

    /** Load an exam the caller may read; 404 if it does not exist or is not visible. */
    public Examination requireExamination(UUID examId) {
        return load(examId, false);
    }

    /** Load an exam the caller may change; 404 if not visible, 403 for a course viewer. */
    public Examination requireExaminationForWrite(UUID examId) {
        return load(examId, true);
    }

    /** Check access to an already loaded exam. */
    public void check(Examination exam, boolean write) {
        UUID courseId = exam.getCourseId();
        if (courseId != null) {
            if (write) courseAccess.requireWrite(courseId);
            else courseAccess.requireRead(courseId);
        } else if (!currentUser.userId().equals(exam.getCreatedBy())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Exam not found");
        }
    }

    private Examination load(UUID examId, boolean write) {
        Examination exam = examRepository.findById(examId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Exam not found"));
        check(exam, write);
        return exam;
    }

    /**
     * Load an exam-scoped child row (task, section, block, ...) and check the caller's access to
     * its exam; 404 if missing or not visible. Centralized so an endpoint can't accidentally skip
     * the exam hop.
     */
    public <T> T requireChild(
        JpaRepository<T, UUID> repository, String id, boolean write,
        Function<T, UUID> examIdOf, String label
    ) {
        T child = repository.findById(id(id))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, label + " not found"));
        load(examIdOf.apply(child), write);
        return child;
    }

    /**
     * Assert that a section exists and belongs to the given exam — guards
     * cross-exam reassignment when a PATCH moves a task/block between sections.
     */
    public void requireSectionInExamination(UUID sectionId, UUID examId) {
        Section section = sectionRepository.findById(sectionId)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Unknown section"));
        if (!section.getExamId().equals(examId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Section belongs to a different exam");
        }
    }

    /**
     * The core course of a LearningGoalHub course, for linking an exam to it. The caller must be
     * able to write in that course; a course the caller is not a member of is a 404.
     */
    public UUID linkCourse(long lghCourseId) {
        int totalPages = 1;
        for (int page = 0; page < totalPages; page++) {
            GoalDirectory.Page<GoalDirectory.Course> res = goalDirectory.listCourses(page, 100);
            if (res == null || res.content() == null) break;
            for (GoalDirectory.Course c : res.content()) {
                if (c.id() == lghCourseId && c.coreCourseId() != null) {
                    courseAccess.requireWrite(c.coreCourseId());
                    return c.coreCourseId();
                }
            }
            totalPages = res.totalPages();
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "Course not found");
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
