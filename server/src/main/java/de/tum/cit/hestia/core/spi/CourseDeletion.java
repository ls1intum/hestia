package de.tum.cit.hestia.core.spi;

import java.util.UUID;

/**
 * Called by {@code CourseApi.delete} inside its transaction, after the owner check and before the
 * course row goes. The database removes a module's rows of the course by cascade; a module
 * implements this for what the cascade cannot reach: stored files (delete them after the commit,
 * e.g. with a {@code TransactionSynchronization}) and alignment links of its objects to goals of
 * other courses. Throwing aborts the deletion.
 */
public interface CourseDeletion {

    void beforeCourseDeleted(UUID courseId);
}
