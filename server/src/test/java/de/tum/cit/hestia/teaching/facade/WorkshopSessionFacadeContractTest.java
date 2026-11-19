package de.tum.cit.hestia.teaching.facade;

import de.tum.cit.hestia.teaching.dto.*;
import de.tum.cit.hestia.teaching.model.WorkshopSessionEntity;
import de.tum.cit.hestia.teaching.repository.WorkshopSessionRepository;
import de.tum.cit.hestia.teaching.usecase.GenerateTimetableUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import de.tum.cit.hestia.core.CourseAccess;
import de.tum.cit.hestia.core.CurrentUser;
import java.util.UUID;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class WorkshopSessionFacadeContractTest {

    @Mock
    private GenerateTimetableUseCase generateTimetableUseCase;

    @Mock
    private WorkshopSessionRepository repository;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Mock
    private CurrentUser currentUser;

    @Mock
    private CourseAccess courseAccess;

    @org.mockito.Spy
    private UsageGuard usageGuard = new UsageGuard(anyone(), 4, 120, 10);

    private final UUID userId = UUID.randomUUID();

    private static CurrentUser anyone() {
        CurrentUser u = org.mockito.Mockito.mock(CurrentUser.class);
        org.mockito.Mockito.when(u.userId()).thenReturn(UUID.randomUUID());
        return u;
    }

    @InjectMocks
    private WorkshopSessionFacade facade;

    @Test
    public void generateAndSaveSession_shouldPassAvailableMaterialsToUseCase() throws Exception {
        org.mockito.Mockito.lenient().when(currentUser.userId()).thenReturn(userId);
        // Arrange
        String materials = "Whiteboard, Jupyter Notebooks";
        WorkshopInputDto meta = new WorkshopInputDto("Test Title", 90, 10, "lecture", null, null, null);
        SessionSkeletonDto skeleton = new SessionSkeletonDto(List.of(), null);
        GenerateSessionRequestDto request = new GenerateSessionRequestDto(List.of(), meta, materials, skeleton);

        WorkshopSessionDto mockResponse = new WorkshopSessionDto("id", "title", "bg", List.of(), null);
        when(generateTimetableUseCase.execute(any(), any(), any(), anyString())).thenReturn(mockResponse);

        WorkshopSessionEntity mockEntity = new WorkshopSessionEntity();
        mockEntity.setId("id");
        mockEntity.setCreatedBy(userId);
        org.mockito.Mockito.lenient().when(repository.findById(anyString())).thenReturn(Optional.of(mockEntity));
        org.mockito.Mockito.lenient().when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        // Act
        facade.generateAndSaveSession(request);

        // Assert
        ArgumentCaptor<String> materialsCaptor = ArgumentCaptor.forClass(String.class);
        verify(generateTimetableUseCase).execute(any(), any(), any(), materialsCaptor.capture());

        assertEquals(materials, materialsCaptor.getValue(), "availableMaterials must be passed unmodified to the UseCase");
    }

    @Test
    public void generateAndSaveSession_shouldNotSaveASingleRegeneratedBlockOverTheTimetable() throws Exception {
        org.mockito.Mockito.lenient().when(currentUser.userId()).thenReturn(userId);
        WorkshopInputDto meta = new WorkshopInputDto("Test Title", 90, 10, "lecture", null, null, null);
        SkeletonBlockDto block = new SkeletonBlockDto("LEARNING_CYCLE", 1, 20, "Cycle", null, List.of());
        SessionSkeletonDto skeleton = new SessionSkeletonDto(List.of(block), "session-1");
        GenerateSessionRequestDto request = new GenerateSessionRequestDto(List.of(), meta, "", skeleton);

        WorkshopSessionDto generated = new WorkshopSessionDto("session-1", "title", "bg", List.of(), null);
        when(generateTimetableUseCase.execute(any(), any(), any(), anyString())).thenReturn(generated);
        WorkshopSessionEntity entity = new WorkshopSessionEntity();
        entity.setId("session-1");
        entity.setCreatedBy(userId); // the caller created the session
        when(repository.findById("session-1")).thenReturn(Optional.of(entity));

        WorkshopSessionDto result = facade.generateAndSaveSession(request);

        assertEquals(generated, result);
        verify(repository, org.mockito.Mockito.never()).save(any());
    }
}
