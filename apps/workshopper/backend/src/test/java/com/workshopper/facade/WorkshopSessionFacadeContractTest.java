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
import java.util.UUID;

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

    @InjectMocks
    private WorkshopSessionFacade facade;

    @Test
    @WithMockUser(username = "dev-local-user")
    public void generateAndSaveSession_shouldPassAvailableMaterialsToUseCase() throws Exception {
        // Arrange
        String materials = "Whiteboard, Jupyter Notebooks";
        WorkshopInputDto meta = new WorkshopInputDto("Test Title", List.of(), 90, 10, "lecture", null, null, null, null, null, null, null, null);
        SessionSkeletonDto skeleton = new SessionSkeletonDto(UUID.randomUUID().toString(), List.of(), List.of(), null);
        GenerateSessionRequestDto request = new GenerateSessionRequestDto(List.of(), meta, materials, skeleton);

        WorkshopSessionDto mockResponse = new WorkshopSessionDto("id", "title", "goal", "bg", null, List.of(), List.of(), null);
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
}
