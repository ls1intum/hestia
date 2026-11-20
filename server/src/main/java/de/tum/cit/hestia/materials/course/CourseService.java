package de.tum.cit.hestia.materials.course;

import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.CourseInfo;
import de.tum.cit.hestia.core.CourseRole;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.GoalRelationInfo;
import de.tum.cit.hestia.core.GoalRole;
import de.tum.cit.hestia.core.LearningGoalApi;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lists, creates, renames and deletes the courses of {@code core} for the materials clients and
 * summarises what each holds. The roles are checked by {@code core}.
 */
@Service
public class CourseService {

    private static final String CONTRIBUTES_TO = "CONTRIBUTES_TO";

    private final MaterialSettingsRepository settingsRepository;
    private final MaterialSettingsService settingsService;
    private final ExtractionStatusLookup extractionStatuses;
    private final CourseApi courseApi;
    private final LearningGoalApi goalApi;

    public CourseService(MaterialSettingsRepository settingsRepository, MaterialSettingsService settingsService,
                         ExtractionStatusLookup extractionStatuses, CourseApi courseApi, LearningGoalApi goalApi) {
        this.settingsRepository = settingsRepository;
        this.settingsService = settingsService;
        this.extractionStatuses = extractionStatuses;
        this.courseApi = courseApi;
        this.goalApi = goalApi;
    }

    @Transactional
    public CourseResponse create(String name, String outputLanguage, Boolean figuresEnabled) {
        // The extraction runs straight after the materials upload that follows this call, so the
        // instructor's language override has to be settable here; afterwards is too late for the
        // first run. Null means "detect the language from the uploaded documents".
        validateOutputLanguage(outputLanguage);
        CourseInfo course = courseApi.create(name);
        MaterialSettings settings = settingsService.settings(course.id());
        settings.setOutputLanguage(outputLanguage);
        settings.setFiguresEnabled(Boolean.TRUE.equals(figuresEnabled));
        settingsRepository.save(settings);
        return CourseResponse.from(course, settings);
    }

    /** One page of the user's courses, newest first (the sort of the request is not used). */
    @Transactional
    public Page<CourseSummaryResponse> summaries(Pageable pageable) {
        List<CourseInfo> page = courseApi.listMine(pageable.getPageNumber(), pageable.getPageSize());
        long total = courseApi.idsMine(CourseRole.VIEWER).size();
        Map<UUID, CourseSummaryResponse> summaries = summarise(page);
        return new PageImpl<>(page.stream().map(c -> summaries.get(c.id())).toList(), pageable, total);
    }

    @Transactional
    public CourseSummaryResponse summary(UUID id) {
        return summarise(List.of(courseApi.get(id))).get(id);
    }

    @Transactional
    public void markSkillsReviewed(UUID id) {
        MaterialSettings settings = settingsService.settings(id);
        if (settings.getSkillsReviewedAt() == null) {
            settings.setSkillsReviewedAt(OffsetDateTime.now());
            settingsRepository.save(settings);
        }
    }

    @Transactional
    public CourseResponse update(UUID id, UpdateCourseRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A request body is required");
        }
        validateOutputLanguage(request.getOutputLanguage());
        CourseInfo course = courseApi.get(id);
        MaterialSettings settings = settingsService.settings(id);
        if (request.getName() != null) {
            String name = request.getName().trim();
            if (name.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank");
            }
            if (name.length() > CourseController.MAX_NAME_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "name must be at most " + CourseController.MAX_NAME_LENGTH + " characters");
            }
            course = courseApi.rename(id, name);
        }
        if (request.hasOutputLanguage()) {
            settings.setOutputLanguage(request.getOutputLanguage());
        }
        if (request.getFiguresEnabled() != null) {
            settings.setFiguresEnabled(request.getFiguresEnabled());
        }
        return CourseResponse.from(course, settingsRepository.save(settings));
    }

    /**
     * Deletes a course and everything that hangs off it. Every {@code course_id} foreign key of the
     * module (documents, hierarchy nodes, runs, exams, settings) and of {@code core} (goals, links)
     * cascades, so removing the course is enough.
     */
    @Transactional
    public void delete(UUID id) {
        courseApi.delete(id);
    }

    /** One summary per course, with the document counts read in one grouped query. */
    private Map<UUID, CourseSummaryResponse> summarise(List<CourseInfo> courses) {
        Map<UUID, CourseSummaryResponse> summaries = new LinkedHashMap<>();
        if (courses.isEmpty()) {
            return summaries;
        }
        List<UUID> ids = courses.stream().map(CourseInfo::id).toList();
        Map<UUID, MaterialSettings> settings = settingsRepository.findByCourseIdIn(ids).stream()
                .collect(Collectors.toMap(MaterialSettings::getCourseId, s -> s));
        Map<UUID, Long> documentCounts = settingsRepository.countDocumentsByCourseIds(ids).stream()
                .collect(Collectors.toMap(MaterialSettingsRepository.CourseCount::getCourseId,
                        MaterialSettingsRepository.CourseCount::getCount));
        Map<UUID, ExtractionStatus> latestStatuses = extractionStatuses.latestByCourse(ids);
        for (CourseInfo course : courses) {
            MaterialSettings s = settings.get(course.id());
            List<GoalInfo> goals = goalApi.goals(course.id());
            List<GoalRelationInfo> relationships = goalApi.relationships(course.id());
            summaries.put(course.id(), new CourseSummaryResponse(
                    course.id(),
                    course.title(),
                    s == null ? null : s.getOutputLanguage(),
                    s != null && s.isFiguresEnabled(),
                    course.createdAt().atOffset(ZoneOffset.UTC),
                    course.personal(),
                    documentCounts.getOrDefault(course.id(), 0L),
                    goals.size(),
                    goals.stream().filter(g -> g.origin() == GoalOrigin.TERMINAL).count(),
                    countSkills(goals, relationships),
                    s == null ? null : s.getSkillsReviewedAt(),
                    latestStatuses.get(course.id())));
        }
        return summaries;
    }

    /**
     * Skill count of a course (a capability in code terms). Mirrors the client's
     * {@code buildTopicForest}: a direct CONTRIBUTES_TO child of a topic is a capability when one of
     * its own children has the SKILL role, or when an instructor added it by hand with the SKILL role.
     */
    static long countSkills(List<GoalInfo> goals, List<GoalRelationInfo> relationships) {
        Map<UUID, GoalInfo> byId = goals.stream().collect(Collectors.toMap(GoalInfo::id, g -> g));
        Set<UUID> withSkillChild = new HashSet<>();
        for (GoalRelationInfo r : relationships) {
            GoalInfo source = byId.get(r.sourceGoalId());
            if (CONTRIBUTES_TO.equals(r.type()) && source != null && source.role() == GoalRole.SKILL) {
                withSkillChild.add(r.targetGoalId());
            }
        }
        Set<UUID> capabilityIds = new HashSet<>();
        for (GoalRelationInfo r : relationships) {
            GoalInfo child = byId.get(r.sourceGoalId());
            GoalInfo topic = byId.get(r.targetGoalId());
            if (child == null || topic == null || !CONTRIBUTES_TO.equals(r.type())
                    || topic.origin() != GoalOrigin.TERMINAL || child.origin() == GoalOrigin.GAP) {
                continue;
            }
            boolean userSkill = "USER_CREATED".equals(child.creationProvenance()) && child.role() == GoalRole.SKILL;
            if (withSkillChild.contains(child.id()) || userSkill) {
                capabilityIds.add(child.id());
            }
        }
        return capabilityIds.size();
    }

    /** Null means auto-detect; otherwise only the languages the pipeline is validated for. */
    private static void validateOutputLanguage(String outputLanguage) {
        if (outputLanguage != null && !outputLanguage.equals("de") && !outputLanguage.equals("en")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "outputLanguage must be null, \"de\", or \"en\"");
        }
    }

    /** The response of creating and updating a course. */
    public record CourseResponse(UUID id, String name, String outputLanguage, boolean figuresEnabled,
                                 boolean personal) {
        static CourseResponse from(CourseInfo course, MaterialSettings settings) {
            return new CourseResponse(course.id(), course.title(), settings.getOutputLanguage(),
                    settings.isFiguresEnabled(), course.personal());
        }
    }
}
