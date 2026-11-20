package de.tum.cit.hestia.assessment.examination;

import de.tum.cit.hestia.assessment.lgh.TaskGoalLinks;
import de.tum.cit.hestia.assessment.storage.StorageService;
import de.tum.cit.hestia.assessment.taskblock.TaskBlockRepository;
import de.tum.cit.hestia.core.OrgContext;
import de.tum.cit.hestia.core.spi.CourseDeletion;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A deleted course takes its exams along (the database cascades them). Before that, the links of
 * their tasks are removed (they may point at goals of other courses), and after the commit the
 * exams' source PDFs and figure objects are deleted from storage, best effort, as
 * {@code DELETE /api/exams/{id}} does for one exam.
 */
@Component
class ExamCourseDeletion implements CourseDeletion {

    private static final Logger log = LoggerFactory.getLogger(ExamCourseDeletion.class);

    private final ExaminationRepository exams;
    private final TaskBlockRepository taskRepository;
    private final TaskGoalLinks goalLinks;
    private final StorageService storage;

    ExamCourseDeletion(ExaminationRepository exams, TaskBlockRepository taskRepository, TaskGoalLinks goalLinks,
            StorageService storage) {
        this.exams = exams;
        this.taskRepository = taskRepository;
        this.goalLinks = goalLinks;
        this.storage = storage;
    }

    @Override
    public void beforeCourseDeleted(UUID courseId) {
        List<Examination> doomed = exams.findByCourseIdInOrderByCreatedAtDesc(List.of(courseId));
        if (doomed.isEmpty()) {
            return;
        }
        List<UUID> tasks = new ArrayList<>();
        for (Examination exam : doomed) {
            taskRepository.findByExamIdOrderByPositionAsc(exam.getId()).forEach(t -> tasks.add(t.getId()));
        }
        goalLinks.unlinkAll(tasks);

        UUID orgId = OrgContext.require();
        List<String> pdfs = doomed.stream().map(Examination::getSourceFileUrl)
                .filter(p -> p != null && !p.isBlank()).toList();
        List<String> figurePrefixes = doomed.stream().map(e -> orgId + "/" + e.getId()).toList();
        Runnable deleteFiles = () -> {
            pdfs.forEach(path -> bestEffort(() -> storage.delete("exam-pdfs", path), path));
            figurePrefixes.forEach(prefix -> bestEffort(() -> storage.deletePrefix("exam-figures", prefix), prefix));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteFiles.run();
                }
            });
        } else {
            deleteFiles.run();
        }
    }

    private static void bestEffort(Runnable action, String what) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Could not delete stored file(s) {} of a deleted course: {}", what, e.getMessage());
        }
    }
}
