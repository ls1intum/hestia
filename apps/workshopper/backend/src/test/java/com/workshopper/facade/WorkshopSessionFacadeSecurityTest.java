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
    private GenerateTimetableUseCase generateTimetableUseCase;

    @Mock
    private com.workshopper.usecase.GenerateSlideBlockUseCase generateSlideBlockUseCase;

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
    public void generateAndSaveSession_shouldThrowAccessDenied_whenSessionBelongsToAnotherUser() {
        // Arrange: session in DB belongs to "user-A"
        String sessionId = "some-existing-session-id";
        WorkshopSessionEntity mockEntity = new WorkshopSessionEntity();
        mockEntity.setId(sessionId);
        mockEntity.setOwnerId("user-A");

        when(repo.findById(sessionId)).thenReturn(Optional.of(mockEntity));

        SessionSkeletonDto skeleton = new SessionSkeletonDto("Goal", List.of(), List.of(), sessionId);
        WorkshopInputDto meta = new WorkshopInputDto("Title", List.of(), 60, 20, "workshop", "", "", "", "", "", List.of(), "");
        GenerateSessionRequestDto request = new GenerateSessionRequestDto(List.of(), meta, "{}", skeleton);

        // Act & Assert: "user-B" cannot generate-and-save on "user-A"'s session
        assertThrows(AccessDeniedException.class, () -> {
            facade.generateAndSaveSession(request);
        });
    }
}
