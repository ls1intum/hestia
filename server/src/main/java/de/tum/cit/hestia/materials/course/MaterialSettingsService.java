package de.tum.cit.hestia.materials.course;

import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.CourseInfo;
import de.tum.cit.hestia.core.OrgContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and locks the materials settings of a course, creating them with the defaults when a course
 * has none yet (every course of {@code core} is a materials course; settings exist only once used).
 * Course roles are checked by {@code core}.
 */
@Service
public class MaterialSettingsService {

    private final MaterialSettingsRepository settings;
    private final CourseApi courseApi;

    public MaterialSettingsService(MaterialSettingsRepository settings, CourseApi courseApi) {
        this.settings = settings;
        this.courseApi = courseApi;
    }

    /** The course (404 when the user cannot read it) with its settings. */
    @Transactional
    public CourseContext context(UUID courseId) {
        CourseInfo info = courseApi.get(courseId);
        MaterialSettings s = settings(courseId);
        return new CourseContext(info.id(), info.title(), s.getOutputLanguage(), s.isFiguresEnabled());
    }

    /** The settings of a readable course, created lazily. */
    @Transactional
    public MaterialSettings settings(UUID courseId) {
        courseApi.get(courseId);
        return settings.findById(courseId).orElseGet(() -> {
            settings.insertDefaults(courseId, OrgContext.require());
            return settings.findById(courseId).orElseThrow();
        });
    }

    /**
     * Locks the settings row until the transaction ends: writes that check before they insert (a
     * duplicate topic, the next lecture order) take it first, so two concurrent requests for one
     * course cannot both pass the check. Creates the row when it is missing.
     */
    @Transactional
    public CourseContext lock(UUID courseId) {
        CourseInfo info = courseApi.get(courseId);
        settings.insertDefaults(courseId, OrgContext.require());
        MaterialSettings s = settings.lockByCourseId(courseId).orElseThrow();
        return new CourseContext(info.id(), info.title(), s.getOutputLanguage(), s.isFiguresEnabled());
    }
}
