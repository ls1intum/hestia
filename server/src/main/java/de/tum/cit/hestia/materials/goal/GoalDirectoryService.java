package de.tum.cit.hestia.materials.goal;

import de.tum.cit.hestia.core.CourseNotFoundException;
import de.tum.cit.hestia.materials.course.CourseGuard;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.server.ResponseStatusException;
import de.tum.cit.hestia.core.spi.GoalDirectory;
import de.tum.cit.hestia.materials.course.CourseRepository;
import de.tum.cit.hestia.materials.course.CourseService;
import de.tum.cit.hestia.materials.course.CourseSummaryResponse;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * The {@link GoalDirectory} port for the other domain modules. It calls the services behind the
 * {@code /api/courses/**} endpoints, so a caller gets what those endpoints return.
 */
@Service
public class GoalDirectoryService implements GoalDirectory {

    private final CourseService courseService;
    private final CourseRepository courseRepository;
    private final LearningGoalQueryService queries;
    private final LearningGoalEditService edits;
    private final LearningGoalRepository goalRepository;
    private final CourseGuard guard;

    public GoalDirectoryService(CourseService courseService,
                                CourseRepository courseRepository,
                                CourseGuard guard,
                                LearningGoalQueryService queries,
                                LearningGoalEditService edits,
                                LearningGoalRepository goalRepository) {
        this.courseService = courseService;
        this.courseRepository = courseRepository;
        this.queries = queries;
        this.edits = edits;
        this.goalRepository = goalRepository;
        this.guard = guard;
    }

    @Override
    public GoalDirectory.Page<GoalDirectory.Course> listCourses(int page, int size) {
        // The order of GET /api/courses: newest first; the id decides between equal timestamps.
        org.springframework.data.domain.Page<CourseSummaryResponse> courses = courseService.summaries(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        Map<Long, UUID> coreIds = courseRepository
                .findAllById(courses.getContent().stream().map(CourseSummaryResponse::id).toList()).stream()
                .collect(Collectors.toMap(c -> c.getId(), c -> c.getCoreCourseId()));
        return new GoalDirectory.Page<>(
                courses.getContent().stream()
                        .map(c -> new GoalDirectory.Course(c.id(), c.name(), coreIds.get(c.id())))
                        .toList(),
                courses.getNumber(), courses.getSize(), courses.getTotalElements(), courses.getTotalPages());
    }

    @Override
    public GoalDirectory.Course createCourse(String name) {
        de.tum.cit.hestia.materials.course.Course course = courseService.create(name, null, null);
        return new GoalDirectory.Course(course.getId(), course.getName(), course.getCoreCourseId());
    }

    @Override
    public List<SessionGoals> goalsBySession(long courseId) {
        requireRead(courseId);
        return queries.bySession(courseId, null, null, null).stream()
                .map(group -> new SessionGoals(
                        group.nodeId(),
                        group.level() == null ? null : group.level().name(),
                        group.label(),
                        group.goals().stream().map(GoalDirectoryService::toGoal).toList()))
                .toList();
    }

    @Override
    public List<Goal> goals(long courseId) {
        requireRead(courseId);
        return queries.all(courseId).stream().map(GoalDirectoryService::toGoal).toList();
    }

    @Override
    public Goal goal(long courseId, long goalId) {
        requireRead(courseId);
        return queries.find(courseId, goalId)
                .map(GoalDirectoryService::toGoal)
                .orElseThrow(() -> new NoSuchElementException(
                        "Goal " + goalId + " not found in course " + courseId));
    }

    @Override
    public void deleteGoal(long courseId, long goalId) {
        requireWrite(courseId);
        if (goalRepository.findByIdAndCourseId(goalId, courseId).isEmpty()) {
            return;
        }
        edits.delete(courseId, goalId);
    }

    /** Unknown course and course of someone else alike: {@link NoSuchElementException}, as the port promises. */
    private void requireRead(long courseId) {
        try {
            guard.requireRead(courseId);
        } catch (ResponseStatusException | CourseNotFoundException e) {
            throw new NoSuchElementException("Course not found: " + courseId);
        }
    }

    private void requireWrite(long courseId) {
        try {
            guard.requireWrite(courseId);
        } catch (ResponseStatusException | CourseNotFoundException e) {
            throw new NoSuchElementException("Course not found: " + courseId);
        }
    }

    /** A goal as the port shows it: enums as names, no hierarchy when the goal sits under no node. */
    public static Goal toGoal(LearningGoalResponse g) {
        Hierarchy hierarchy = g.hierarchy() == null
                ? null
                : new Hierarchy(g.hierarchy().module(), g.hierarchy().session(), g.hierarchy().exercise());
        List<Relationship> relationships = g.relationships().stream()
                .map(r -> new Relationship(name(r.type()), r.targetGoalId(), r.targetText(),
                        r.confidence(), name(r.origin())))
                .toList();
        return new Goal(g.id(), g.text(), name(g.kind()), name(g.role()), name(g.origin()), name(g.status()),
                name(g.bloomLevel()), name(g.soloLevel()), g.lectureOrder(), hierarchy, relationships);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
