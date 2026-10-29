package com.workshopper.facade;

import com.workshopper.dto.GenerateSessionRequestDto;
import com.workshopper.dto.SessionSkeletonDto;
import com.workshopper.dto.WorkshopInputDto;
import com.workshopper.model.WorkshopSessionEntity;
import com.workshopper.repository.WorkshopSessionRepository;
import com.workshopper.usecase.GenerateTimetableUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import com.workshopper.model.CourseEntity;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Security unit test: verifies that the Facade rejects access to sessions owned
 * by a different user. This test is INTENTIONALLY a unit test (no Spring context,
 * no DB) so it runs fast and cannot be bypassed by schema or config issues.
 */
@ExtendWith(MockitoExtension.class)
public class WorkshopSessionFacadeSecurityTest {

    @Mock
    private WorkshopSessionRepository repo;

    @Mock
    private com.workshopper.repository.CourseRepository courseRepo;

    @Mock
    private com.workshopper.repository.SlideTemplateRepository slideTemplateRepo;

    @Mock
    private GenerateTimetableUseCase generateTimetableUseCase;

    @Mock
    private com.workshopper.usecase.GenerateSlideBlockUseCase generateSlideBlockUseCase;

    @org.mockito.Spy
    private UsageGuard usageGuard = new UsageGuard(4, 120, 10);

    @InjectMocks
    private WorkshopSessionFacade facade;

    @BeforeEach
    void setUpAuth() {
        // Simulate Spring Security context: the current user is "user-B"
        var auth = new UsernamePasswordAuthenticationToken("user-B", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        
        // inject objectMapper via reflection since @InjectMocks doesn't cover it
        try {
            var field = WorkshopSessionFacade.class.getDeclaredField("mapper");
            field.setAccessible(true);
            field.set(facade, new ObjectMapper());
        } catch (Exception ignored) {}
    }


    @Test
    public void verifyOwnership_shouldThrow_whenCourseBelongsToAnotherUser() {
        CourseEntity course = new CourseEntity();
        course.setId("course-1"); course.setOwnerId("user-A");
        when(courseRepo.findById("course-1")).thenReturn(Optional.of(course));
        assertThrows(AccessDeniedException.class, () -> facade.verifyOwnership("course-1"));
    }

    @Test
    public void verifyOwnership_shouldAllow_whenCourseBelongsToCurrentUser() {
        CourseEntity course = new CourseEntity();
        course.setId("course-1"); course.setOwnerId("user-B");
        when(courseRepo.findById("course-1")).thenReturn(Optional.of(course));
        assertDoesNotThrow(() -> facade.verifyOwnership("course-1"));
    }
    @Test
    public void generateAndSaveSession_shouldThrowAccessDenied_whenSessionBelongsToAnotherUser() {
        // Arrange: session in DB belongs to "user-A"
        String sessionId = "some-existing-session-id";
        WorkshopSessionEntity mockEntity = new WorkshopSessionEntity();
        mockEntity.setId(sessionId);
        mockEntity.setOwnerId("user-A");

        when(repo.findById(sessionId)).thenReturn(Optional.of(mockEntity));

        SessionSkeletonDto skeleton = new SessionSkeletonDto(List.of(), sessionId);
        WorkshopInputDto meta = new WorkshopInputDto("Title", 60, 20, "workshop", "", "", List.of());
        GenerateSessionRequestDto request = new GenerateSessionRequestDto(List.of(), meta, "{}", skeleton);

        // Act & Assert: "user-B" cannot generate-and-save on "user-A"'s session
        assertThrows(AccessDeniedException.class, () -> {
            facade.generateAndSaveSession(request);
        });
    }

    @Test
    public void verifyOwnership_shouldReturn404_whenTheSessionNoLongerExists() {
        // Deleted in another tab: saving must not quietly re-create it
        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> facade.verifyOwnership("deleted-id"));
        org.junit.jupiter.api.Assertions.assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    public void verifyOwnership_shouldDeny_whenTheSessionHasNoOwner() {
        WorkshopSessionEntity orphan = new WorkshopSessionEntity();
        orphan.setId("orphan");
        when(repo.findById("orphan")).thenReturn(Optional.of(orphan));
        assertThrows(AccessDeniedException.class, () -> facade.verifyOwnership("orphan"));
    }

    @Test
    public void generateAndSaveSession_shouldRejectOversizedRequests_beforeCallingTheLlm() throws Exception {
        var block = new com.workshopper.dto.SkeletonBlockDto("LEARNING_CYCLE", 1, 10, "b", null, List.of());
        var blocks = java.util.Collections.nCopies(5_000, block);
        WorkshopInputDto meta = new WorkshopInputDto("Title", 60, 20, "workshop", "", "", List.of());
        var request = new GenerateSessionRequestDto(List.of(), meta, "", new SessionSkeletonDto(blocks, null));

        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> facade.generateAndSaveSession(request));
        org.junit.jupiter.api.Assertions.assertEquals(400, ex.getStatusCode().value());
        org.mockito.Mockito.verifyNoInteractions(generateTimetableUseCase);
    }

    @Test
    public void exportCourseZip_shouldExplain_whenNoSessionIsReady() {
        when(courseRepo.findById("course-1")).thenReturn(Optional.empty());
        when(repo.findAllByCourseIdOrdered("course-1")).thenReturn(List.of());
        var ex = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> facade.exportCourseZip("course-1"));
        org.junit.jupiter.api.Assertions.assertEquals(400, ex.getStatusCode().value());
    }
}
