package de.tum.cit.hestia.teaching.controller;

import de.tum.cit.hestia.teaching.dto.CourseGoalDto;
import de.tum.cit.hestia.teaching.dto.CourseGoalInputDto;
import de.tum.cit.hestia.teaching.dto.CourseSummaryDto;
import de.tum.cit.hestia.teaching.dto.SaveDraftRequestDto;
import de.tum.cit.hestia.teaching.dto.SessionDetailDto;
import de.tum.cit.hestia.teaching.facade.WorkshopSessionFacade;
import de.tum.cit.hestia.teaching.service.SessionGoals;
import de.tum.cit.hestia.teaching.service.WorkshopService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Courses: group sessions and hold the settings and slide template that new sessions inherit. */
@RestController
@RequestMapping("/api/workshop/courses")
public class CourseController {

    private final WorkshopService workshopService;
    private final WorkshopSessionFacade facade;
    private final SessionGoals goals;

    public CourseController(WorkshopService workshopService, WorkshopSessionFacade facade, SessionGoals goals) {
        this.workshopService = workshopService;
        this.facade = facade;
        this.goals = goals;
    }

    /** The learning goals of the course (core), for the goal picker; needs VIEWER. */
    @GetMapping("/{id}/goals")
    public List<CourseGoalDto> courseGoals(@PathVariable String id) {
        facade.verifyCourseRead(id);
        return goals.courseGoals(WorkshopService.uuid(id));
    }

    /**
     * Saves the goals written or edited on the goal step in core (without id: created, with id: changed
     * if the text differs) and returns them in the given order; needs EDITOR.
     */
    @PutMapping("/{id}/goals")
    public List<CourseGoalDto> saveCourseGoals(@PathVariable String id, @RequestBody List<CourseGoalInputDto> inputs) {
        facade.verifyCourseWrite(id);
        return goals.saveCourseGoals(WorkshopService.uuid(id), inputs);
    }

    @GetMapping
    public List<CourseSummaryDto> listCourses() {
        return workshopService.listCourses();
    }

    /** The course settings (as draft state) and template; 404 if unknown or not a member, needs VIEWER. */
    @GetMapping("/{id}")
    public ResponseEntity<SessionDetailDto> getCourseDetail(@PathVariable String id) {
        facade.verifyCourseRead(id);
        return ResponseEntity.of(workshopService.getCourse(id));
    }

    /** Creates or updates a course draft; returns {"id": ...}. */
    @PostMapping("/draft")
    public Map<String, String> saveCourseDraft(@RequestBody SaveDraftRequestDto request) {
        return Map.of("id", workshopService.saveCourseDraft(request));
    }

    @PutMapping("/{id}/rename")
    public ResponseEntity<Map<String, Object>> renameCourse(@PathVariable String id, @RequestBody Map<String, String> body) {
        String newTitle = body.get("title");
        if (newTitle == null || newTitle.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));
        }
        if (!workshopService.renameCourse(id, newTitle.trim())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCourse(@PathVariable String id) {
        if (!workshopService.deleteCourse(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}
