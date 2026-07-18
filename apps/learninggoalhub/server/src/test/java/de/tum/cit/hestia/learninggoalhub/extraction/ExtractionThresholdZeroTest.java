package de.tum.cit.hestia.learninggoalhub.extraction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import de.tum.cit.hestia.learninggoalhub.document.Document;
import de.tum.cit.hestia.learninggoalhub.document.DocumentRepository;
import de.tum.cit.hestia.learninggoalhub.embedding.EmbeddingService;
import de.tum.cit.hestia.learninggoalhub.goal.GoalKind;
import de.tum.cit.hestia.learninggoalhub.taxonomy.TaxonomyService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "hestia.extraction.direct-max-chars=0")
class ExtractionThresholdZeroTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @MockitoBean
    private ExtractionService extractionService;

    @MockitoBean
    private SessionExtractionService sessionExtractionService;

    @MockitoBean
    private SessionGoalConsolidator sessionGoalConsolidator;

    @MockitoBean
    private TaxonomyService taxonomyService;

    @MockitoBean
    private EmbeddingService embeddingService;

    @Test
    void zeroThresholdDisablesDirectPath() throws Exception {
        Course course = courseRepository.save(new Course("Threshold comparison"));
        String text = "Small text still uses the legacy path when direct extraction is disabled.";
        documentRepository.save(new Document(course, "legacy.pdf", "text/plain", text));

        ExtractedGoal candidate = new ExtractedGoal("Explain the legacy path.", GoalKind.EXPLICIT,
                "...legacy path...");
        when(extractionService.extract(eq(text), eq(null))).thenReturn(List.of(candidate));
        when(sessionGoalConsolidator.consolidate(anyString(), anyList(), eq(null)))
                .thenReturn(List.of(new ConsolidatedGoal(candidate.text(), List.of(0))));
        when(embeddingService.embedAll(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(ignored -> new float[4096]).toList();
        });

        mockMvc.perform(post("/api/courses/{id}/extract", course.getId()))
                .andExpect(status().isOk());

        verify(extractionService).extract(eq(text), eq(null));
        verify(sessionExtractionService, never()).extract(anyString(), anyString(), eq(null));
    }
}
