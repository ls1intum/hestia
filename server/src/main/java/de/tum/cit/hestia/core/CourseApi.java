package de.tum.cit.hestia.core;

import java.util.List;
import java.util.UUID;

/**
 * Courses of {@code core}. Every method checks the current user's role itself: reading needs a
 * membership, renaming {@code EDITOR}, deleting {@code OWNER}.
 */
public interface CourseApi {

    /** Creates a course in the user's organisation; the user becomes its {@code OWNER}. */
    CourseInfo create(String title);

    CourseInfo get(UUID courseId);

    /** The courses the user is a member of, newest first. */
    List<CourseInfo> listMine(int page, int size);

    /** All ids of the courses the user is a member of with at least {@code role}. */
    List<UUID> idsMine(CourseRole role);

    CourseInfo rename(UUID courseId, String title);

    void delete(UUID courseId);
}
