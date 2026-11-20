package de.tum.cit.hestia.materials.hierarchy;

import de.tum.cit.hestia.materials.TestTenant;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.tum.cit.hestia.materials.MaterialsTestBase;
import de.tum.cit.hestia.materials.MaterialsTestConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@Import(MaterialsTestConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class HierarchyNodeControllerTest extends MaterialsTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HierarchyNodeRepository hierarchyNodeRepository;

    @Test
    void renamesHierarchyNode() throws Exception {
        UUID course = TestTenant.course("Operating Systems");
        HierarchyNode node = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.SESSION, "Session 1"));

        mockMvc.perform(patch("/learninggoalhub/api/courses/{courseId}/hierarchy-nodes/{nodeId}", course, node.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"  Scheduling  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(node.getId()))
                .andExpect(jsonPath("$.label").value("Scheduling"));
    }

    @Test
    void rejectsBlankLabel() throws Exception {
        UUID course = TestTenant.course("Compilers");
        HierarchyNode node = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.SESSION, "Session 1"));

        mockMvc.perform(patch("/learninggoalhub/api/courses/{courseId}/hierarchy-nodes/{nodeId}", course, node.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void renameReturns404WhenNodeBelongsToOtherCourse() throws Exception {
        UUID course = TestTenant.course("Networks");
        UUID otherCourse = TestTenant.course("Security");
        HierarchyNode node = hierarchyNodeRepository.save(
                new HierarchyNode(course, null, HierarchyLevel.SESSION, "Session 1"));

        mockMvc.perform(patch("/learninggoalhub/api/courses/{courseId}/hierarchy-nodes/{nodeId}", otherCourse, node.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"X\"}"))
                .andExpect(status().isNotFound());
    }
}
