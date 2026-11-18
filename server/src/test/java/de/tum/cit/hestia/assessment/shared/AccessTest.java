package de.tum.cit.hestia.assessment.shared;

import de.tum.cit.hestia.assessment.error.ApiException;
import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.assessment.section.SectionRepository;
import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.CourseAccessDeniedException;
import de.tum.cit.hestia.core.CourseRole;
import de.tum.cit.hestia.core.CurrentUser;
import de.tum.cit.hestia.core.spi.GoalDirectory;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Access is the single choke-point every controller funnels through. An exam without a course is
 * its creator's alone (everyone else gets a 404); an exam with a course follows the course roles;
 * and a malformed id never becomes a 500.
 */
class AccessTest {

    private final ExaminationRepository exams = mock(ExaminationRepository.class);
    private final SectionRepository sections = mock(SectionRepository.class);
    private final CourseAccess courseAccess = mock(CourseAccess.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final Access access = new Access(exams, sections, courseAccess, currentUser, mock(GoalDirectory.class));

    private static Examination createdBy(UUID creator, UUID courseId) {
        Examination e = new Examination();
        e.setCreatedBy(creator);
        e.setCourseId(courseId);
        return e;
    }

    private UUID stored(Examination exam) {
        UUID examId = UUID.randomUUID();
        when(exams.findById(examId)).thenReturn(Optional.of(exam));
        return examId;
    }

    @Test
    void returnsACourselessExaminationToItsCreator() {
        UUID me = UUID.randomUUID();
        when(currentUser.userId()).thenReturn(me);
        Examination exam = createdBy(me, null);

        assertThat(access.requireExamination(stored(exam))).isSameAs(exam);
        assertThat(access.requireExaminationForWrite(stored(exam))).isSameAs(exam);
    }

    @Test
    void hidesACourselessExaminationOfAnotherUserAs404() {
        when(currentUser.userId()).thenReturn(UUID.randomUUID());
        UUID examId = stored(createdBy(UUID.randomUUID(), null));

        for (var call : new java.util.function.Consumer[] {
                id -> access.requireExamination((UUID) id), id -> access.requireExaminationForWrite((UUID) id)}) {
            assertThatThrownBy(() -> call.accept(examId))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Test
    void anExaminationOfNobodyIsHiddenFromEveryone() {
        // The creator's account was deleted (created_by set null) and there is no course.
        when(currentUser.userId()).thenReturn(UUID.randomUUID());
        UUID examId = stored(createdBy(null, null));

        assertThatThrownBy(() -> access.requireExamination(examId)).isInstanceOf(ApiException.class);
    }

    @Test
    void anExaminationWithACourseFollowsTheCourseRoles() {
        UUID course = UUID.randomUUID();
        // Not the creator: the course decides.
        when(currentUser.userId()).thenReturn(UUID.randomUUID());
        Examination exam = createdBy(UUID.randomUUID(), course);
        UUID examId = stored(exam);

        assertThat(access.requireExamination(examId)).isSameAs(exam);
        verify(courseAccess).requireRead(course);

        assertThat(access.requireExaminationForWrite(examId)).isSameAs(exam);
        verify(courseAccess).requireWrite(course);
    }

    @Test
    void aViewerOfTheCourseMayReadButNotWrite() {
        UUID course = UUID.randomUUID();
        doThrow(new CourseAccessDeniedException(course, CourseRole.EDITOR)).when(courseAccess).requireWrite(course);
        UUID examId = stored(createdBy(UUID.randomUUID(), course));

        assertThat(access.requireExamination(examId)).isNotNull();
        assertThatThrownBy(() -> access.requireExaminationForWrite(examId))
            .isInstanceOf(CourseAccessDeniedException.class);
    }

    @Test
    void unknownExaminationIs404() {
        UUID examId = UUID.randomUUID();
        when(exams.findById(examId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireExamination(examId))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void malformedPathIdMapsTo404NotServerError() {
        assertThatThrownBy(() -> Access.id("not-a-uuid"))
            .isInstanceOf(ApiException.class)
            .satisfies(ex -> assertThat(((ApiException) ex).status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void wellFormedIdParses() {
        UUID id = UUID.randomUUID();
        assertThat(Access.id(id.toString())).isEqualTo(id);
    }
}
