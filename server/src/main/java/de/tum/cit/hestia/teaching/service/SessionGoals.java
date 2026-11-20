package de.tum.cit.hestia.teaching.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.tum.cit.hestia.core.AlignmentApi;
import de.tum.cit.hestia.core.AlignmentLinkInfo;
import de.tum.cit.hestia.core.DomainModule;
import de.tum.cit.hestia.core.GoalInfo;
import de.tum.cit.hestia.core.GoalOrigin;
import de.tum.cit.hestia.core.LearningGoalApi;
import de.tum.cit.hestia.core.NewGoal;
import de.tum.cit.hestia.teaching.dto.CourseGoalDto;
import de.tum.cit.hestia.teaching.dto.CourseGoalInputDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The learning goals of a session. The goals themselves live in {@code core}; a session's plan
 * ({@code refinedGoals} in its draft state) keeps only the id of each goal ({@code goalId}) and the
 * fields Workshopper owns (prerequisites, activities, the session label, sub-skills). When the plan
 * is read, the text and levels are resolved from core again, so a goal renamed there shows its new
 * wording without any import. One alignment link of type {@code SESSION} connects each goal of the
 * plan with the session.
 */
@Service
public class SessionGoals {

    public static final String LINK_TYPE = "SESSION";

    /** The server's limits, the same as UsageGuard's: goals per session and characters per goal. */
    private static final int MAX_GOALS = 20;
    private static final int MAX_GOAL_CHARS = 3_000;

    /** The fields of a plan entry that belong to Workshopper and are stored. */
    private static final List<String> OWN_FIELDS =
            List.of("prerequisites", "achieveActivities", "assessActivities", "session", "subSkills");

    private final LearningGoalApi goals;
    private final AlignmentApi alignment;
    private final ObjectMapper mapper = new ObjectMapper();

    public SessionGoals(LearningGoalApi goals, AlignmentApi alignment) {
        this.goals = goals;
        this.alignment = alignment;
    }

    // ── The goal picker and the goal step ─────────────────────────────────────

    /** The goals of the course (needs membership). */
    public List<CourseGoalDto> courseGoals(UUID courseId) {
        return goals.goals(courseId).stream().map(SessionGoals::dto).toList();
    }

    /**
     * Saves the goals written or edited on the goal step in core: goals with an id are changed when
     * their text differs, goals without one are created (needs EDITOR). Returns the goals in the given order.
     */
    public List<CourseGoalDto> saveCourseGoals(UUID courseId, List<CourseGoalInputDto> inputs) {
        if (inputs.size() > MAX_GOALS) {
            throw bad("A session can have at most " + MAX_GOALS + " learning goals.");
        }
        Map<UUID, GoalInfo> existing = goalsById(inputs.stream().map(i -> parseId(i.id())).toList());
        List<CourseGoalDto> saved = new ArrayList<>();
        for (CourseGoalInputDto input : inputs) {
            String text = input.text() == null ? "" : input.text().trim();
            if (text.isEmpty()) throw bad("A learning goal needs a text.");
            if (text.length() > MAX_GOAL_CHARS) throw bad("A learning goal is too long (at most " + MAX_GOAL_CHARS + " characters).");
            UUID id = parseId(input.id());
            if (id == null) {
                saved.add(dto(goals.create(courseId, NewGoal.of(text, GoalOrigin.MANUAL, DomainModule.TEACHING))));
                continue;
            }
            GoalInfo goal = existing.get(id);
            if (goal == null || !goal.courseId().equals(courseId) || goal.removed()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This learning goal does not exist in the course.");
            }
            saved.add(dto(goal.text().equals(text) ? goal : goals.update(id, goal.asChange().withText(text))));
        }
        return saved;
    }

    // ── The plan of a session ─────────────────────────────────────────────────

    /** The goal ids of the stored plan ({@code refinedGoals}), in order; null if the draft has no plan. */
    public List<UUID> planGoalIds(String draftStateJson) {
        JsonNode plan = planOf(parse(draftStateJson));
        if (plan == null) return null;
        List<UUID> ids = new ArrayList<>();
        plan.forEach(entry -> ids.add(parseId(entry.path("goalId").asText(null))));
        return ids;
    }

    /**
     * The draft state as it is stored: every plan entry keeps its goal id and the fields Workshopper
     * owns; goal text and levels are dropped. Entries must refer to goals of the session's course
     * (400 otherwise, e.g. a goal that was never saved to core).
     */
    public String toStored(String draftStateJson, UUID courseId) {
        JsonNode root = parse(draftStateJson);
        JsonNode plan = planOf(root);
        if (plan == null) return draftStateJson;
        if (plan.size() > MAX_GOALS) throw bad("A session can have at most " + MAX_GOALS + " learning goals.");
        List<UUID> ids = new ArrayList<>();
        for (JsonNode entry : plan) {
            String raw = entry.hasNonNull("goalId") ? entry.path("goalId").asText() : entry.path("id").asText(null);
            UUID id = parseId(raw);
            if (id == null) throw bad("A learning goal of the plan has not been saved to the course yet.");
            ids.add(id);
        }
        Map<UUID, GoalInfo> byId = goalsById(ids);
        ArrayNode stored = mapper.createArrayNode();
        for (int i = 0; i < ids.size(); i++) {
            GoalInfo goal = byId.get(ids.get(i));
            if (goal == null || goal.removed() || !goal.courseId().equals(courseId)) {
                throw bad("A learning goal of the plan does not belong to the session's course.");
            }
            JsonNode entry = plan.get(i);
            ObjectNode out = mapper.createObjectNode();
            out.put("goalId", ids.get(i).toString());
            for (String field : OWN_FIELDS) {
                if (entry.has(field)) out.set(field, entry.get(field));
            }
            stored.add(out);
        }
        ((ObjectNode) root).set("refinedGoals", stored);
        return write(root);
    }

    /**
     * The draft state as the client reads it: each plan entry gets {@code id} (= goal id), the
     * current {@code goal} text, {@code originalGoal}, {@code bloomLevel} and {@code soloLevel} from
     * core. Entries whose goal was removed in core or cannot be read any more are left out.
     */
    public String toResolved(String draftStateJson, String sessionId) {
        JsonNode root = parse(draftStateJson);
        JsonNode plan = planOf(root);
        if (plan == null) return draftStateJson;
        List<UUID> ids = new ArrayList<>();
        plan.forEach(entry -> ids.add(parseId(entry.path("goalId").asText(null))));
        Map<UUID, GoalInfo> byId = goalsById(ids);
        Map<UUID, AlignmentLinkInfo> reviews = new java.util.HashMap<>();
        if (sessionId != null) {
            for (AlignmentLinkInfo link : alignment.goalsFor(DomainModule.TEACHING, LINK_TYPE, sessionId)) {
                if ("REVIEW".equals(link.reviewState())) reviews.put(link.goalId(), link);
            }
        }
        ArrayNode resolved = mapper.createArrayNode();
        for (int i = 0; i < ids.size(); i++) {
            GoalInfo goal = ids.get(i) == null ? null : byId.get(ids.get(i));
            if (goal == null || goal.removed()) continue;
            ObjectNode out = mapper.createObjectNode();
            out.put("id", goal.id().toString());
            out.put("goalId", goal.id().toString());
            out.put("goal", goal.text());
            out.put("originalGoal", goal.originalText());
            if (goal.bloomLevel() != null) out.put("bloomLevel", goal.bloomLevel());
            if (goal.soloLevel() != null) out.put("soloLevel", goal.soloLevel());
            AlignmentLinkInfo review = reviews.get(goal.id());
            if (review != null) {
                out.put("review", review.reviewReason());
                out.put("reviewLinkId", review.id().toString());
            }
            JsonNode entry = plan.get(i);
            for (String field : OWN_FIELDS) {
                if (entry.has(field)) out.set(field, entry.get(field));
            }
            // The client's plan entries always carry these lists
            for (String list : List.of("prerequisites", "achieveActivities", "assessActivities")) {
                if (!out.has(list)) out.set(list, mapper.createArrayNode());
            }
            resolved.add(out);
        }
        ((ObjectNode) root).set("refinedGoals", resolved);
        return write(root);
    }

    /**
     * Drops the plan entries and kept goal edits that refer to goals of the given course from a
     * stored draft state (a session leaving that course): the goals stay in their course, the
     * session no longer uses them.
     */
    public String withoutGoalsOfCourse(String draftStateJson, UUID courseId) {
        JsonNode root = parse(draftStateJson);
        if (!(root instanceof ObjectNode object)) return draftStateJson;
        for (String key : List.of("refinedGoals", "keptGoals")) {
            JsonNode list = object.get(key);
            if (list == null || !list.isArray()) continue;
            List<UUID> ids = new ArrayList<>();
            list.forEach(e -> ids.add(parseId(e.has("goalId") ? e.path("goalId").asText(null) : e.path("id").asText(null))));
            Map<UUID, GoalInfo> byId = goalsById(ids);
            ArrayNode kept = mapper.createArrayNode();
            for (int i = 0; i < ids.size(); i++) {
                GoalInfo goal = ids.get(i) == null ? null : byId.get(ids.get(i));
                if (goal == null || !goal.courseId().equals(courseId)) kept.add(list.get(i));
            }
            object.set(key, kept);
        }
        return write(object);
    }

    // ── Alignment links ───────────────────────────────────────────────────────

    /** Makes the session's links the goals of its plan: one link per goal, none for goals that left the plan. */
    public void syncLinks(String sessionId, Collection<UUID> planGoalIds) {
        Set<UUID> wanted = planGoalIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<UUID> linked = alignment.goalsFor(DomainModule.TEACHING, LINK_TYPE, sessionId).stream()
                .map(AlignmentLinkInfo::goalId).collect(Collectors.toSet());
        for (UUID goalId : wanted) {
            if (!linked.contains(goalId)) alignment.link(goalId, DomainModule.TEACHING, LINK_TYPE, sessionId);
        }
        for (UUID goalId : linked) {
            if (!wanted.contains(goalId)) alignment.unlink(goalId, DomainModule.TEACHING, LINK_TYPE, sessionId);
        }
    }

    /** Number of links marked for review, by session id, for the given sessions. */
    public Map<String, Integer> openReviews(Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) return Map.of();
        Map<String, Integer> counts = new java.util.HashMap<>();
        alignment.goalsForTargets(DomainModule.TEACHING, LINK_TYPE, sessionIds).forEach((id, links) -> {
            int n = (int) links.stream().filter(l -> "REVIEW".equals(l.reviewState())).count();
            if (n > 0) counts.put(id, n);
        });
        return counts;
    }

    /** Removes all links of a session (it was deleted). */
    public void unlinkSession(String sessionId) {
        alignment.unlinkTarget(DomainModule.TEACHING, LINK_TYPE, sessionId);
    }

    /** Removes the session's links to goals of the course it leaves. */
    public void unlinkSessionInCourse(String sessionId, UUID oldCourseId) {
        alignment.unlinkTargetInCourse(DomainModule.TEACHING, LINK_TYPE, sessionId, oldCourseId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<UUID, GoalInfo> goalsById(List<UUID> ids) {
        List<UUID> known = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (known.isEmpty()) return Map.of();
        return goals.goalsById(known).stream().collect(Collectors.toMap(GoalInfo::id, Function.identity()));
    }

    private static JsonNode planOf(JsonNode root) {
        if (root == null || !root.isObject()) return null;
        JsonNode plan = root.get("refinedGoals");
        return plan != null && plan.isArray() ? plan : null;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw bad("The draft state is not valid JSON.");
        }
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static UUID parseId(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static CourseGoalDto dto(GoalInfo goal) {
        return new CourseGoalDto(goal.id().toString(), goal.text(), goal.originalText(),
                goal.bloomLevel(), goal.soloLevel(), goal.status().name());
    }
}
