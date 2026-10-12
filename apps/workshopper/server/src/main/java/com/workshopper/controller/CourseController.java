package com.workshopper.controller;

import com.workshopper.config.AuthContext;
import com.workshopper.dto.CourseSummaryDto;
import com.workshopper.dto.SaveDraftRequestDto;
import com.workshopper.dto.SessionDetailDto;
import com.workshopper.facade.WorkshopSessionFacade;
import com.workshopper.service.WorkshopService;
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

    public CourseController(WorkshopService workshopService, WorkshopSessionFacade facade) {
        this.workshopService = workshopService;
        this.facade = facade;
    }

    @GetMapping
    public List<CourseSummaryDto> listCourses() {
        return workshopService.listCourses(AuthContext.getCurrentUserId());
    }

    /** The course settings (as draft state) and template; 404 if unknown, 403 if not the user's. */
    @GetMapping("/{id}")
    public ResponseEntity<SessionDetailDto> getCourseDetail(@PathVariable String id) {
        facade.verifyCourseOwnership(id);
        return ResponseEntity.of(workshopService.getCourse(id));
    }

    /** Creates or updates a course draft; returns {"id": ...}. */
    @PostMapping("/draft")
    public Map<String, String> saveCourseDraft(@RequestBody SaveDraftRequestDto request) {
        return Map.of("id", workshopService.saveCourseDraft(request, AuthContext.getCurrentUserId()));
    }

    @PutMapping("/{id}/rename")
    public ResponseEntity<Map<String, Object>> renameCourse(@PathVariable String id, @RequestBody Map<String, String> body) {
        String newTitle = body.get("title");
        if (newTitle == null || newTitle.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Title is required"));
        }
        if (!workshopService.renameCourse(id, newTitle.trim(), AuthContext.getCurrentUserId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCourse(@PathVariable String id) {
        if (!workshopService.deleteCourse(id, AuthContext.getCurrentUserId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}
