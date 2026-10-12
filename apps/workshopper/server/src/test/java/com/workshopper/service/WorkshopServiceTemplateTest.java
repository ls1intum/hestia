package com.workshopper.service;

import com.workshopper.model.CourseEntity;
import com.workshopper.model.SlideTemplateEntity;
import com.workshopper.model.WorkshopSessionEntity;
import com.workshopper.repository.CourseRepository;
import com.workshopper.repository.SlideTemplateRepository;
import com.workshopper.repository.WorkshopSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Which slide template applies to a session: its own, its course's, or the default design. */
class WorkshopServiceTemplateTest {

    private static final byte[] COURSE_FILE = {1};
    private static final byte[] SESSION_FILE = {2};

    private WorkshopSessionRepository sessions;
    private CourseRepository courses;
    private SlideTemplateRepository templates;
    private WorkshopService service;
    private WorkshopSessionEntity session;

    @BeforeEach
    void setUp() {
        sessions = mock(WorkshopSessionRepository.class);
        courses = mock(CourseRepository.class);
        templates = mock(SlideTemplateRepository.class);
        service = new WorkshopService(null, sessions, courses, templates);

        CourseEntity course = new CourseEntity();
        course.setId("course-1");
        course.setOwnerId("owner");
        course.setTemplateId("course-1");
        when(courses.findById("course-1")).thenReturn(Optional.of(course));
        when(templates.findById("course-1")).thenReturn(Optional.of(template("course-1", COURSE_FILE)));
        when(templates.findById("session-1")).thenReturn(Optional.of(template("session-1", SESSION_FILE)));

        session = new WorkshopSessionEntity();
        session.setId("session-1");
        session.setOwnerId("owner");
        session.setCourseId("course-1");
        when(sessions.findById("session-1")).thenReturn(Optional.of(session));
        when(sessions.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void sessionInCourseInheritsCourseTemplate() {
        assertThat(service.getTemplate("session-1")).isEqualTo(COURSE_FILE);
    }

    @Test
    void sessionsOwnTemplateWinsOverCourseTemplate() {
        session.setTemplateId("session-1");
        assertThat(service.getTemplate("session-1")).isEqualTo(SESSION_FILE);
    }

    @Test
    void removingTemplateFromSessionUsesDefaultAndKeepsCourseTemplate() {
        session.setTemplateId("session-1");

        service.deleteTemplate("session-1");

        assertThat(service.getTemplate("session-1")).isNull();
        verify(templates).deleteById("session-1");
        verify(templates, never()).deleteById("course-1");
    }

    @Test
    void removingInheritedCourseTemplateOnlyOptsThisSessionOut() {
        service.deleteTemplate("session-1");

        assertThat(service.getTemplate("session-1")).isNull();
        verify(templates, never()).deleteById(any());
    }

    @Test
    void uploadingOwnTemplateAfterOptingOutAppliesIt() {
        service.deleteTemplate("session-1");
        service.saveTemplate("session-1", SESSION_FILE, "mine.pptx");

        assertThat(session.isUseDefaultTemplate()).isFalse();
        assertThat(session.getTemplateId()).isEqualTo("session-1");
    }

    @Test
    void movingSessionToAnotherCourseInheritsThatCourseTemplate() {
        session.setCourseId("course-0");
        service.deleteTemplate("session-1");
        service.moveSession("session-1", "course-1");

        assertThat(service.getTemplate("session-1")).isEqualTo(COURSE_FILE);
    }

    @Test
    void reorderingWithinTheSameCourseKeepsTheOptOut() {
        service.deleteTemplate("session-1");
        service.moveSession("session-1", "course-1");

        assertThat(service.getTemplate("session-1")).isNull();
    }

    private static SlideTemplateEntity template(String id, byte[] data) {
        SlideTemplateEntity t = new SlideTemplateEntity();
        t.setId(id);
        t.setFileData(data);
        return t;
    }
}
