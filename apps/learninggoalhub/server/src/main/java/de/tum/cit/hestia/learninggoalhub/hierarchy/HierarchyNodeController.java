package de.tum.cit.hestia.learninggoalhub.hierarchy;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/courses/{courseId}/hierarchy-nodes")
public class HierarchyNodeController {

    private final HierarchyNodeRepository hierarchyNodeRepository;

    public HierarchyNodeController(HierarchyNodeRepository hierarchyNodeRepository) {
        this.hierarchyNodeRepository = hierarchyNodeRepository;
    }

    @PatchMapping("/{nodeId}")
    @Transactional
    public HierarchyNodeResponse update(@PathVariable Long courseId,
                                        @PathVariable Long nodeId,
                                        @RequestBody UpdateHierarchyNodeRequest request) {
        HierarchyNode node = hierarchyNodeRepository.findById(nodeId)
                .filter(n -> n.getCourse().getId().equals(courseId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Hierarchy node not found: " + nodeId));
        String label = request == null || request.label() == null ? null : request.label().trim();
        if (label == null || label.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label must not be blank");
        }
        node.setLabel(label);
        return HierarchyNodeResponse.from(hierarchyNodeRepository.save(node));
    }

    public record UpdateHierarchyNodeRequest(String label) {
    }

    public record HierarchyNodeResponse(Long id, Long courseId, HierarchyLevel level, String label) {
        static HierarchyNodeResponse from(HierarchyNode node) {
            return new HierarchyNodeResponse(
                    node.getId(), node.getCourse().getId(), node.getLevel(), node.getLabel());
        }
    }
}
