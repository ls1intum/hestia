package com.workshopper.facade;

import com.workshopper.dto.*;
import com.workshopper.model.WorkshopSessionEntity;
import com.workshopper.repository.WorkshopSessionRepository;
import com.workshopper.usecase.GenerateTimetableUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.test.context.support.WithMockUser;

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

    @org.mockito.Spy
    private UsageGuard usageGuard = new UsageGuard(4, 120, 10);

    @InjectMocks
    private WorkshopSessionFacade facade;

    @Test
    @WithMockUser(username = "dev-local-user")
    public void generateAndSaveSession_shouldPassAvailableMaterialsToUseCase() throws Exception {
        // Arrange
        String materials = "Whiteboard, Jupyter Notebooks";
        WorkshopInputDto meta = new WorkshopInputDto("Test Title", 90, 10, "lecture", null, null, null);
        SessionSkeletonDto skeleton = new SessionSkeletonDto(List.of(), null);
        GenerateSessionRequestDto request = new GenerateSessionRequestDto(List.of(), meta, materials, skeleton);

        WorkshopSessionDto mockResponse = new WorkshopSessionDto("id", "title", "bg", List.of(), null);
        when(generateTimetableUseCase.execute(any(), any(), any(), anyString())).thenReturn(mockResponse);

        WorkshopSessionEntity mockEntity = new WorkshopSessionEntity();
        mockEntity.setId("id");
        mockEntity.setOwnerId("dev-local-user");
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
    @WithMockUser(username = "dev-local-user")
    public void generateAndSaveSession_shouldNotSaveASingleRegeneratedBlockOverTheTimetable() throws Exception {
        WorkshopInputDto meta = new WorkshopInputDto("Test Title", 90, 10, "lecture", null, null, null);
        SkeletonBlockDto block = new SkeletonBlockDto("LEARNING_CYCLE", 1, 20, "Cycle", null, List.of());
        SessionSkeletonDto skeleton = new SessionSkeletonDto(List.of(block), "session-1");
        GenerateSessionRequestDto request = new GenerateSessionRequestDto(List.of(), meta, "", skeleton);

        WorkshopSessionDto generated = new WorkshopSessionDto("session-1", "title", "bg", List.of(), null);
        when(generateTimetableUseCase.execute(any(), any(), any(), anyString())).thenReturn(generated);
        WorkshopSessionEntity entity = new WorkshopSessionEntity();
        entity.setId("session-1");
        entity.setOwnerId(com.workshopper.config.AuthContext.getCurrentUserId()); // the caller owns the session
        when(repository.findById("session-1")).thenReturn(Optional.of(entity));

        WorkshopSessionDto result = facade.generateAndSaveSession(request);

        assertEquals(generated, result);
        verify(repository, org.mockito.Mockito.never()).save(any());
    }
}
