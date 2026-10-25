package de.tum.cit.hestia.learninggoalhub.course;

import de.tum.cit.hestia.learninggoalhub.extraction.ExtractionRun;
import de.tum.cit.hestia.learninggoalhub.extraction.ExtractionRunRepository;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Creates, lists, updates and deletes courses, and summarises what each course holds. */
@Service
public class CourseService {

    private final CourseRepository courseRepository;
    private final ExtractionRunRepository extractionRunRepository;

    public CourseService(CourseRepository courseRepository, ExtractionRunRepository extractionRunRepository) {
        this.courseRepository = courseRepository;
        this.extractionRunRepository = extractionRunRepository;
    }

    @Transactional
    public Course create(String name, String outputLanguage, Boolean figuresEnabled) {
        // The extraction runs straight after the materials upload that follows this call, so the
        // instructor's language override has to be settable here — afterwards is too late for the
        // first run. Null means "detect the language from the uploaded documents".
        validateOutputLanguage(outputLanguage);
        Course course = new Course(name);
        course.setOutputLanguage(outputLanguage);
        course.setFiguresEnabled(Boolean.TRUE.equals(figuresEnabled));
        return courseRepository.save(course);
    }

    @Transactional(readOnly = true)
    public Page<CourseSummaryResponse> summaries(Pageable pageable) {
        Page<Course> page = courseRepository.findAll(pageable);
        Map<Long, CourseSummaryResponse> summaries = summarise(page.getContent());
        return page.map(course -> summaries.get(course.getId()));
    }

    @Transactional(readOnly = true)
    public CourseSummaryResponse summary(Long id) {
        Course course = courseRepository.findOrThrow(id);
        return summarise(List.of(course)).get(id);
    }

    @Transactional
    public void markSkillsReviewed(Long id) {
        Course course = courseRepository.findOrThrow(id);
        if (course.getSkillsReviewedAt() == null) {
            course.setSkillsReviewedAt(OffsetDateTime.now());
            courseRepository.save(course);
        }
    }

    @Transactional
    public Course update(Long id, UpdateCourseRequest request) {
        Course course = courseRepository.findOrThrow(id);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A request body is required");
        }
        validateOutputLanguage(request.getOutputLanguage());
        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank");
            }
            if (name.length() > CourseController.MAX_NAME_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "name must be at most " + CourseController.MAX_NAME_LENGTH + " characters");
            }
            course.setName(name);
        }
        if (request.hasOutputLanguage()) {
            course.setOutputLanguage(request.getOutputLanguage());
        }
        if (request.getFiguresEnabled() != null) {
            course.setFiguresEnabled(request.getFiguresEnabled());
        }
        return courseRepository.save(course);
    }

    @Transactional
    public void delete(Long id) {
        courseRepository.requireExists(id);
        courseRepository.deleteById(id);
    }

    /** One summary per course, with every count read in one grouped query per kind. */
    private Map<Long, CourseSummaryResponse> summarise(List<Course> courses) {
        List<Long> ids = courses.stream().map(Course::getId).toList();
        Map<Long, CourseSummaryResponse> summaries = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return summaries;
        }
        Map<Long, Long> goalCounts = toCountMap(courseRepository.countGoalsByCourseIds(ids));
        Map<Long, Long> topicCounts = toCountMap(courseRepository.countTopicsByCourseIds(ids));
        Map<Long, Long> skillCounts = toCountMap(courseRepository.countSkillsByCourseIds(ids));
        Map<Long, Long> documentCounts = toCountMap(courseRepository.countDocumentsByCourseIds(ids));
        Map<Long, ExtractionRun.Status> extractionStatuses = latestExtractionStatuses(ids);
        for (Course course : courses) {
            summaries.put(course.getId(), new CourseSummaryResponse(
                    course.getId(),
                    course.getName(),
                    course.getOutputLanguage(),
                    course.isFiguresEnabled(),
                    course.getCreatedAt(),
                    documentCounts.getOrDefault(course.getId(), 0L),
                    goalCounts.getOrDefault(course.getId(), 0L),
                    topicCounts.getOrDefault(course.getId(), 0L),
                    skillCounts.getOrDefault(course.getId(), 0L),
                    course.getSkillsReviewedAt(),
                    extractionStatuses.get(course.getId())));
        }
        return summaries;
    }

    /** Null means auto-detect; otherwise only the languages the pipeline is validated for. */
    private static void validateOutputLanguage(String outputLanguage) {
        if (outputLanguage != null && !outputLanguage.equals("de") && !outputLanguage.equals("en")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "outputLanguage must be null, \"de\", or \"en\"");
        }
    }

    private static Map<Long, Long> toCountMap(List<CourseRepository.CourseCount> counts) {
        return counts.stream().collect(Collectors.toMap(
                CourseRepository.CourseCount::getCourseId,
                CourseRepository.CourseCount::getCount));
    }

    private Map<Long, ExtractionRun.Status> latestExtractionStatuses(List<Long> courseIds) {
        Map<Long, ExtractionRun.Status> statuses = new LinkedHashMap<>();
        for (ExtractionRun run : extractionRunRepository.findLatestCandidatesByCourseIds(courseIds)) {
            statuses.putIfAbsent(run.getCourse().getId(), run.getStatus());
        }
        return statuses;
    }
}
