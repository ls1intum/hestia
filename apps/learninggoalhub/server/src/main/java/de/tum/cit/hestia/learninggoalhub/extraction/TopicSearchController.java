package de.tum.cit.hestia.learninggoalhub.extraction;

import de.tum.cit.hestia.learninggoalhub.extraction.TopicSearchService.PagePlan;
import de.tum.cit.hestia.learninggoalhub.extraction.TopicSearchService.PageRange;
import de.tum.cit.hestia.learninggoalhub.extraction.TopicSearchService.ProposalResponse;
import de.tum.cit.hestia.learninggoalhub.goal.LearningGoalController.LearningGoalResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Find in the slides": adds a topic from the pages that teach it. Propose pages, read the chosen
 * pages into a proposal, then create the topic from the sub-skills the instructor kept.
 */
@RestController
@RequestMapping("/api/courses/{courseId}/learning-goals/topic-search")
public class TopicSearchController {

    private final TopicSearchService topicSearchService;

    public TopicSearchController(TopicSearchService topicSearchService) {
        this.topicSearchService = topicSearchService;
    }

    /** Proposes page runs that mention the topic. */
    @PostMapping("/pages")
    public PagePlan pages(@PathVariable Long courseId,
                          @RequestParam(required = false) String model,
                          @RequestBody TopicRequest request) {
        return topicSearchService.planPages(courseId, request.text(), model);
    }

    /** Reads the chosen pages into a proposal held for thirty minutes; nothing is saved. */
    @PostMapping
    public ProposalResponse search(@PathVariable Long courseId,
                                   @RequestParam(required = false) String model,
                                   @RequestBody SearchRequest request) {
        return topicSearchService.search(courseId, request.text(), request.ranges(), model);
    }

    /** Creates the topic with the kept sub-skills, their sources and knowledge. */
    @PostMapping("/{proposalId}")
    @ResponseStatus(HttpStatus.CREATED)
    public LearningGoalResponse accept(@PathVariable Long courseId,
                                       @PathVariable UUID proposalId,
                                       @RequestBody AcceptRequest request) {
        return LearningGoalResponse.from(
                topicSearchService.accept(courseId, proposalId, request.subSkillKeys()), List.of(), List.of());
    }

    public record TopicRequest(String text) {
    }

    public record SearchRequest(String text, List<PageRange> ranges) {
    }

    public record AcceptRequest(List<String> subSkillKeys) {
    }
}
