package de.tum.cit.hestia.learninggoalhub.hierarchy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.learninggoalhub.TestcontainersConfiguration;
import de.tum.cit.hestia.learninggoalhub.course.Course;
import de.tum.cit.hestia.learninggoalhub.course.CourseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HierarchyNodeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private HierarchyNodeRepository hierarchyNodeRepository;

    @Test
    void renamesHierarchyNode() throws Exception {
        Course course = courseRepository.save(new Course("Operating Systems"));
        HierarchyNode node = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.SESSION, "Session 1"));

        mockMvc.perform(patch("/api/courses/{courseId}/hierarchy-nodes/{nodeId}", course.getId(), node.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"  Scheduling  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(node.getId()))
                .andExpect(jsonPath("$.label").value("Scheduling"));
    }

    @Test
    void rejectsBlankLabel() throws Exception {
        Course course = courseRepository.save(new Course("Compilers"));
        HierarchyNode node = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.SESSION, "Session 1"));

        mockMvc.perform(patch("/api/courses/{courseId}/hierarchy-nodes/{nodeId}", course.getId(), node.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void renameReturns404WhenNodeBelongsToOtherCourse() throws Exception {
        Course course = courseRepository.save(new Course("Networks"));
        Course otherCourse = courseRepository.save(new Course("Security"));
        HierarchyNode node = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.SESSION, "Session 1"));

        mockMvc.perform(patch("/api/courses/{courseId}/hierarchy-nodes/{nodeId}", otherCourse.getId(), node.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"X\"}"))
                .andExpect(status().isNotFound());
    }
}
