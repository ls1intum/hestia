package de.tum.cit.hestia.teaching.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.CourseAccessDeniedException;
import de.tum.cit.hestia.core.CourseNotFoundException;
import de.tum.cit.hestia.core.CourseRole;
import de.tum.cit.hestia.core.CurrentUser;
import de.tum.cit.hestia.teaching.dto.GenerateSessionRequestDto;
import de.tum.cit.hestia.teaching.dto.SessionSkeletonDto;
import de.tum.cit.hestia.teaching.dto.WorkshopInputDto;
import de.tum.cit.hestia.teaching.model.WorkshopSessionEntity;
import de.tum.cit.hestia.teaching.repository.TeachingPlanRepository;
import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.teaching.repository.SlideTemplateRepository;
import de.tum.cit.hestia.teaching.repository.WorkshopSessionRepository;
import de.tum.cit.hestia.teaching.usecase.GenerateSlideBlockUseCase;
import de.tum.cit.hestia.teaching.usecase.GenerateTimetableUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Security unit test: the facade checks course roles through {@link CourseAccess}; a session has no
 * access rules of its own. Intentionally a unit test (no Spring context, no DB).
 */
@ExtendWith(MockitoExtension.class)
public class WorkshopSessionFacadeSecurityTest {

    @Mock private WorkshopSessionRepository repo;
    @Mock private TeachingPlanRepository plans;
    @Mock private CourseApi courseApi;
    @Mock private SlideTemplateRepository slideTemplateRepo;
    @Mock private GenerateTimetableUseCase generateTimetableUseCase;
    @Mock private GenerateSlideBlockUseCase generateSlideBlockUseCase;
    @Mock private CourseAccess courseAccess;
    @Mock private CurrentUser currentUser;

    @org.mockito.Spy
    private UsageGuard usageGuard = new UsageGuard(userWithId(), 4, 120, 10);

    @InjectMocks
    private WorkshopSessionFacade facade;

    private final UUID me = UUID.randomUUID();
    private final UUID someoneElse = UUID.randomUUID();

    private static CurrentUser userWithId() {
        CurrentUser u = org.mockito.Mockito.mock(CurrentUser.class);
        when(u.userId()).thenReturn(UUID.randomUUID());
        return u;
    }

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(currentUser.userId()).thenReturn(me);
        var field = WorkshopSessionFacade.class.getDeclaredField("mapper");
        field.setAccessible(true);
        field.set(facade, new ObjectMapper());
    }


    @Test
    public void courseRead_needsViewer_andWriteNeedsEditor() {
        UUID core = UUID.randomUUID();

        facade.verifyCourseRead(core.toString());
        verify(courseAccess).requireRead(core);

        facade.verifyCourseWrite(core.toString());
        verify(courseAccess).requireWrite(core);

        facade.verifyCourseOwner(core.toString());
        verify(courseAccess).requireOwner(core);

        // Not a course id at all
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> facade.verifyCourseRead("course-1"))
                .getStatusCode().value());
    }

    @Test
    public void viewerCannotWrite() {
        UUID core = UUID.randomUUID();
        when(courseAccess.canRead(core)).thenReturn(true);
        doThrow(new CourseAccessDeniedException(core, CourseRole.EDITOR)).when(courseAccess).requireWrite(core);

        assertDoesNotThrow(() -> facade.verifyRead(core.toString()));
        assertThrows(CourseAccessDeniedException.class, () -> facade.verifyWrite(core.toString()));
    }

    @Test
    public void nonMemberGetsNotFound() {
        UUID core = UUID.randomUUID();
        doThrow(new CourseNotFoundException(core)).when(courseAccess).requireRead(core);

        assertThrows(CourseNotFoundException.class, () -> facade.verifyCourseRead(core.toString()));
    }

    @Test
    public void sessionInACourseIsCheckedOnTheCourse() {
        UUID core = UUID.randomUUID();
        WorkshopSessionEntity session = new WorkshopSessionEntity();
        session.setId("s1");
        session.setCourseId(core);
        session.setCreatedBy(someoneElse);
        when(repo.findById("s1")).thenReturn(Optional.of(session));

        // Not the creator, but a member with the role: allowed
        assertDoesNotThrow(() -> facade.verifyWrite("s1"));
        verify(courseAccess).requireWrite(core);
    }

    @Test
    public void generateAndSaveSession_shouldBeRefused_whenTheUserCannotWriteTheSessionsCourse() {
        UUID core = UUID.randomUUID();
        WorkshopSessionEntity theirs = new WorkshopSessionEntity();
        theirs.setId("some-existing-session-id");
        theirs.setCourseId(core);
        theirs.setCreatedBy(someoneElse);
        when(repo.findById("some-existing-session-id")).thenReturn(Optional.of(theirs));
        doThrow(new CourseNotFoundException(core)).when(courseAccess).requireWrite(core);

        SessionSkeletonDto skeleton = new SessionSkeletonDto(List.of(), "some-existing-session-id");
        WorkshopInputDto meta = new WorkshopInputDto("Title", 60, 20, "workshop", "", "", List.of());
        GenerateSessionRequestDto request = new GenerateSessionRequestDto(List.of(), meta, "{}", skeleton);

        assertThrows(CourseNotFoundException.class, () -> facade.generateAndSaveSession(request));
        verifyNoInteractions(generateTimetableUseCase);
    }

    @Test
    public void verify_shouldReturn404_whenTheSessionNoLongerExists() {
        // Deleted in another tab: saving must not quietly re-create it
        var ex = assertThrows(ResponseStatusException.class, () -> facade.verifyWrite("deleted-id"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    public void generateAndSaveSession_shouldRejectOversizedRequests_beforeCallingTheLlm() throws Exception {
        var block = new de.tum.cit.hestia.teaching.dto.SkeletonBlockDto("LEARNING_CYCLE", 1, 10, "b", null, List.of());
        var blocks = java.util.Collections.nCopies(5_000, block);
        WorkshopInputDto meta = new WorkshopInputDto("Title", 60, 20, "workshop", "", "", List.of());
        var request = new GenerateSessionRequestDto(List.of(), meta, "", new SessionSkeletonDto(blocks, null));

        var ex = assertThrows(ResponseStatusException.class, () -> facade.generateAndSaveSession(request));
        assertEquals(400, ex.getStatusCode().value());
        verifyNoInteractions(generateTimetableUseCase);
    }

    @Test
    public void exportCourseZip_shouldExplain_whenNoSessionIsReady() {
        UUID course = UUID.randomUUID();
        when(plans.findById(course)).thenReturn(Optional.empty());
        when(repo.findAllByCourseIdOrdered(course)).thenReturn(List.of());
        var ex = assertThrows(ResponseStatusException.class, () -> facade.exportCourseZip(course.toString()));
        assertEquals(400, ex.getStatusCode().value());
    }
}
