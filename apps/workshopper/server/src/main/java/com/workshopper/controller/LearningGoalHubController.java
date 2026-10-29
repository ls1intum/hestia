package com.workshopper.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.workshopper.service.LearningGoalHubClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** LearningGoalHub reads for the goal import, forwarded with LGH's service key (see {@link LearningGoalHubClient}). */
@RestController
@RequestMapping("/api/workshop/lgh")
public class LearningGoalHubController {

    private final LearningGoalHubClient learningGoalHub;

    public LearningGoalHubController(LearningGoalHubClient learningGoalHub) {
        this.learningGoalHub = learningGoalHub;
    }

    @GetMapping("/courses")
    public JsonNode courses(@RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "100") int size) {
        return learningGoalHub.courses(page, size);
    }

    @GetMapping("/courses/{courseId}/learning-goals/by-session")
    public JsonNode goalsBySession(@PathVariable long courseId) {
        return learningGoalHub.goalsBySession(courseId);
    }
}
