package de.tum.cit.hestia.core;

/** A user's role in a course. Each role includes the rights of the ones before it. */
public enum CourseRole {
    /** Reads. */
    VIEWER,
    /** Reads and writes content. */
    EDITOR,
    /** Also manages members and deletes the course. */
    OWNER;

    public boolean includes(CourseRole other) {
        return compareTo(other) >= 0;
    }
}
