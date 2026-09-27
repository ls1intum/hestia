package com.workshopper.controller;

import com.workshopper.dto.CourseSummaryDto;
import com.workshopper.dto.SaveDraftRequestDto;
import com.workshopper.model.CourseEntity;
import com.workshopper.repository.CourseRepository;
import com.workshopper.service.WorkshopService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workshop/courses")
public class CourseController {

    private static final Logger log = LoggerFactory.getLogger(CourseController.class);


    private final CourseRepository courseRepository;
    private final WorkshopService workshopService;

    public CourseController(CourseRepository courseRepository, WorkshopService workshopService) {
        this.courseRepository = courseRepository;
        this.workshopService = workshopService;
    }

    @GetMapping
    public ResponseEntity<List<CourseSummaryDto>> listCourses() {
        String ownerId = com.workshopper.config.AuthContext.getCurrentUserId();
        List<CourseSummaryDto> courses = courseRepository.findByOwnerIdOrdered(ownerId).stream()
                .map(c -> new CourseSummaryDto(
                        c.getId(),
                        c.getTitle(),
                        c.getStatus(),
                        c.getCurrentStep(),
                        c.getCreatedAt(),
                        c.getUpdatedAt()
                )).toList();
        return ResponseEntity.ok(courses);
    }

    @PostMapping("/draft")
    public ResponseEntity<?> saveCourseDraft(@RequestBody SaveDraftRequestDto request) {
        try {
            String ownerId = com.workshopper.config.AuthContext.getCurrentUserId();
            String id = workshopService.saveCourseDraft(request, ownerId);
            return ResponseEntity.ok(Map.of("id", id));
        } catch (Exception e) {
            log.error("Course draft save failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCourse(@PathVariable String id) {
        try {
            String ownerId = com.workshopper.config.AuthContext.getCurrentUserId();
            workshopService.deleteCourse(id, ownerId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Course delete failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<com.workshopper.dto.SessionDetailDto> getCourseDetail(@PathVariable String id) {
        String ownerId = com.workshopper.config.AuthContext.getCurrentUserId();
        CourseEntity course = courseRepository.findById(id).orElseThrow();
        if (!course.getOwnerId().equals(ownerId)) {
            return ResponseEntity.status(403).build();
        }
        
        com.workshopper.dto.SessionDetailDto dto = new com.workshopper.dto.SessionDetailDto(
                course.getId(),
                course.getTitle(),
                course.getStatus(),
                course.getCurrentStep(),
                null, // courseId
                course.getDraftStateJson(),
                null // session
        );
        return ResponseEntity.ok(dto);
    }

}
