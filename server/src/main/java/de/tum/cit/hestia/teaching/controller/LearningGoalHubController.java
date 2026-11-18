package de.tum.cit.hestia.teaching.controller;

import de.tum.cit.hestia.core.spi.GoalDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Courses and learning goals of the materials module for the goal import, read through
 * {@link GoalDirectory}. The JSON keeps the shape the client got from LearningGoalHub's REST API:
 * a course page is {@code {content: [...], page: {size, number, totalElements, totalPages}}}.
 */
@RestController
@RequestMapping("/api/workshop/lgh")
public class LearningGoalHubController {

    private final GoalDirectory goals;

    public LearningGoalHubController(GoalDirectory goals) {
        this.goals = goals;
    }

    @GetMapping("/courses")
    public Map<String, Object> courses(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "100") int size) {
        GoalDirectory.Page<GoalDirectory.Course> courses = goals.listCourses(page, size);
        Map<String, Object> pageInfo = new LinkedHashMap<>();
        pageInfo.put("size", courses.size());
        pageInfo.put("number", courses.number());
        pageInfo.put("totalElements", courses.totalElements());
        pageInfo.put("totalPages", courses.totalPages());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", courses.content());
        body.put("page", pageInfo);
        return body;
    }

    @GetMapping("/courses/{courseId}/learning-goals/by-session")
    public List<GoalDirectory.SessionGoals> goalsBySession(@PathVariable long courseId) {
        try {
            return goals.goalsBySession(courseId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found.");
        }
    }
}
