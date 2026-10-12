package com.workshopper.service;

import com.workshopper.dto.SaveDraftRequestDto;
import com.workshopper.model.WorkshopSessionEntity;
import com.workshopper.repository.CourseRepository;
import com.workshopper.repository.SlideTemplateRepository;
import com.workshopper.repository.WorkshopSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A session's status follows whether it has a timetable, not which step it was saved on. */
class WorkshopServiceStatusTest {

    private WorkshopSessionRepository sessions;
    private WorkshopService service;
    private WorkshopSessionEntity entity;

    @BeforeEach
    void setUp() {
        sessions = mock(WorkshopSessionRepository.class);
        service = new WorkshopService(sessions, mock(CourseRepository.class), mock(SlideTemplateRepository.class));
        entity = new WorkshopSessionEntity();
        entity.setId("s1");
        entity.setOwnerId("u");
        when(sessions.findById("s1")).thenReturn(Optional.of(entity));
        when(sessions.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private void save(String step, String draftJson) {
        service.saveDraft(new SaveDraftRequestDto("s1", step, "Title", draftJson, null), "u");
    }

    @Test
    void editingTheTimetableKeepsAGeneratedSessionComplete() {
        save("timeline", "{\"session\":{\"title\":\"Title\",\"blocks\":[]}}");
        assertEquals("complete", entity.getStatus());
        save("final-review", "{\"session\":{\"title\":\"Title\",\"blocks\":[]}}");
        assertEquals("complete", entity.getStatus());
    }

    @Test
    void aSessionWithoutATimetableIsADraft() {
        save("goals", "{\"workshopInput\":{\"duration\":90}}");
        assertEquals("draft", entity.getStatus());
        save("timeline", "{\"workshopInput\":{\"duration\":90}}"); // generation failed: no timetable yet
        assertEquals("draft", entity.getStatus());
    }
}
