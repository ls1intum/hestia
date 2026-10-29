package com.workshopper.usecase;

import com.workshopper.dto.*;
import com.workshopper.service.LlmService;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class GenerateTimetableUseCase {

    private final LlmService llm;
    private final ExecutorService llmExecutor = Executors.newFixedThreadPool(20);

    public GenerateTimetableUseCase(LlmService llm) {
        this.llm = llm;
    }

    public WorkshopSessionDto execute(WorkshopInputDto input, 
                                      SessionSkeletonDto skeleton, 
                                      List<LearningGoalPlanDto> goals,
                                      String availableMaterials
                                      ) throws Exception {

        
        ObjectMapper mapper = new ObjectMapper();

        String sessionTypeLabel = "workshop".equals(input.sessionType()) ? "interactive workshop" : "university lecture";

        StringBuilder goalsStr = new StringBuilder();
        if (goals != null) {
            goalsStr.append("For each learning goal below: match the complexity of generated content and activities to its Bloom's/SOLO level; ")
                    .append("use its listed prerequisite concepts as the core content to teach during that goal's Inform phase; ")
                    .append("treat its suggested activity only as a content idea for that goal's Process/Practice activity: deliver it through one of the SELECTED ACTIVITIES using only the AVAILABLE MATERIALS, and never introduce an activity type that is not selected.\n\n");
            for (int i = 0; i < goals.size(); i++) {
                var g = goals.get(i);
                goalsStr.append("Goal ").append(i + 1).append(": ").append(g.goal());
                if (g.bloomLevel() != null && !g.bloomLevel().isBlank()) {
                    goalsStr.append(" [Bloom's Level: ").append(g.bloomLevel()).append("]");
                }
                if (g.soloLevel() != null && !g.soloLevel().isBlank()) {
                    goalsStr.append(" [Target SOLO Level: ").append(g.soloLevel()).append("]");
                }
                goalsStr.append("\n");
                if (g.prerequisites() != null && !g.prerequisites().isEmpty()) {
                    goalsStr.append("  Content to teach: ").append(String.join("; ", g.prerequisites())).append("\n");
                }
                if (g.achieveActivities() != null && !g.achieveActivities().isEmpty()) {
                    goalsStr.append("  Suggested learning-cycle activity: ").append(String.join("; ", g.achieveActivities())).append("\n");
                }
            }
        }
        String goalsString = goalsStr.toString();
        
        // No selected activities means no restriction (null); otherwise methods the user
        // picked explicitly in the evaluate mapping count as selected too
        boolean activitiesRestricted = input.selectedActivities() != null && !input.selectedActivities().isEmpty();
        List<String> selectedActivities = null;
        if (activitiesRestricted) {
            selectedActivities = new ArrayList<>(input.selectedActivities());
            if (input.evaluateMappings() != null) {
                for (var m : input.evaluateMappings()) {
                    if (m.method() != null) selectedActivities.add(m.method());
                }
            }
        }
        List<String> allowedActivities = selectedActivities;
        List<String> materials = parseMaterials(availableMaterials);
        String materialsStr = materials.isEmpty() ? "NONE" : String.join(", ", materials);

        List<ActivityBlockDto> generatedBlocks = new ArrayList<>();
        List<Future<ActivityBlockDto>> blockFutures = new ArrayList<>();

        if (skeleton.blocks() != null) {
            
            // Filter out 0-duration sections from the skeleton blocks before sending to LLM
            List<SkeletonBlockDto> filteredBlocks = skeleton.blocks().stream()
                    .map(block -> {
                        if (block.sections() == null || block.sections().isEmpty())
                            return block;
                        var filteredSections = block.sections().stream()
                                .filter(s -> s.duration() > 0)
                                .collect(Collectors.toList());
                        return new SkeletonBlockDto(
                                block.phase(), block.lgIndex(), block.duration(),
                                block.title(), block.description(), filteredSections);
                    })
                    .collect(Collectors.toList());
                    
            SessionSkeletonDto filteredSkeleton = new SessionSkeletonDto(
                    filteredBlocks, skeleton.sessionId());
                    
            String skeletonBlocksStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(filteredSkeleton.blocks());

            for (SkeletonBlockDto block : filteredBlocks) {
                // Prepare stringified values for LLM
                String targetBlockStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(block);
                String selectedActivitiesStr = activitiesRestricted ? String.join(", ", input.selectedActivities()) : "ANY";
                
                String cycleActivity = learningCycleActivity(block, goals, input.selectedActivities());
                List<EvaluateSlot> evaluateSlots = evaluateSlots(block, goals, input);

                Future<ActivityBlockDto> future = llmExecutor.submit(() -> {
                    String rules = cycleActivity != null
                            ? buildLearningCycleRules(cycleActivity)
                            : buildEvaluateRules(evaluateSlots);
                    ActivityBlockDto hydrated = llm.hydrateActivityBlock(input, sessionTypeLabel, selectedActivitiesStr, goalsString, skeletonBlocksStr, targetBlockStr, rules, materialsStr);
                    ActivityBlockDto restricted = restrictToSelections(hydrated, allowedActivities, materials);
                    if (cycleActivity != null) return withSingleMethod(restricted, cycleActivity);
                    return normalizeEvaluateSteps(restricted, evaluateSlots);
                });
                blockFutures.add(future);
            }
            
            // Wait for all async block generations to complete
            for (Future<ActivityBlockDto> f : blockFutures) {
                generatedBlocks.add(f.get());
            }
        }

        // Generate a concise session title
        String skeletonBlocksStr = "";
        try {
            skeletonBlocksStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(skeleton.blocks());
        } catch(Exception e) {}
        
        String title = llm.generateSessionTitle(input, sessionTypeLabel, skeletonBlocksStr, goalsString).trim();


        return new WorkshopSessionDto(
                UUID.randomUUID().toString(),
                title,
                input.studentBackground(),
                generatedBlocks,
                null
        );
    }

    
    /** One assessment box of the Check Understanding block: an activity, the goals it covers and its minutes. */
    record EvaluateSlot(String method, List<Integer> goalNumbers, int minutes) {
        /** The step prefix the client parses, e.g. "Goal 1, 2 & 3 - Worked Problem". */
        String label() {
            List<String> nums = goalNumbers.stream().map(String::valueOf).collect(Collectors.toList());
            String goalsPart = nums.size() == 1 ? nums.get(0)
                    : String.join(", ", nums.subList(0, nums.size() - 1)) + " & " + nums.get(nums.size() - 1);
            return "Goal " + goalsPart + " - " + method;
        }
    }

    /** The user's activity-to-goal mapping, or the Bloom's/SOLO default when there is none. */
    private List<EvaluateMappingDto> resolveEvaluateMappings(List<LearningGoalPlanDto> goals, WorkshopInputDto meta) {
        var evaluateMappings = meta.evaluateMappings();
        if ((evaluateMappings == null || evaluateMappings.isEmpty()) && goals != null && !goals.isEmpty()) {
            // Goals that end up with the same activity share one assessment, like a user-made mapping
            Map<String, List<String>> goalsByMethod = new LinkedHashMap<>();
            for (int i = 0; i < goals.size(); i++) {
                var g = goals.get(i);
                List<String> selected = meta.selectedActivities() != null ? meta.selectedActivities() : List.of();
                // Best fit for the goal's Bloom's/SOLO level first, then the goal's own assessment activity
                String method = ActivityRecommender.recommend(g, selected);
                if (method == null && g.assessActivities() != null && !g.assessActivities().isEmpty()) {
                    method = selected.isEmpty() ? g.assessActivities().get(0) : matchSelection(g.assessActivities().get(0), selected);
                }
                if (method == null) {
                    method = !selected.isEmpty()
                            ? selected.get(i % selected.size())
                            : "Quiz / Polls"; // last-resort only, when nothing was selected or suggested
                }
                goalsByMethod.computeIfAbsent(method, k -> new ArrayList<>()).add(g.id());
            }
            evaluateMappings = new ArrayList<>();
            for (var e : goalsByMethod.entrySet()) {
                evaluateMappings.add(new EvaluateMappingDto(e.getKey(), e.getValue()));
            }
        }
        return evaluateMappings != null ? evaluateMappings : List.of();
    }

    /** Turns the mapping into boxes with goal numbers and minutes that add up to the block's duration. */
    private List<EvaluateSlot> evaluateSlots(SkeletonBlockDto targetBlock, List<LearningGoalPlanDto> goals, WorkshopInputDto meta) {
        if (!"EVALUATE".equalsIgnoreCase(targetBlock.phase()) || goals == null) return List.of();
        List<EvaluateSlot> slots = new ArrayList<>();
        List<EvaluateMappingDto> mappings = resolveEvaluateMappings(goals, meta).stream()
                .filter(m -> m.method() != null && m.lgIds() != null && !m.lgIds().isEmpty())
                .collect(Collectors.toList());
        if (mappings.isEmpty()) return slots;

        int total = targetBlock.duration() > 0 ? targetBlock.duration() : 10;
        int base = Math.max(1, total / mappings.size());
        int remainder = Math.max(0, total - base * mappings.size());
        for (int m = 0; m < mappings.size(); m++) {
            List<Integer> nums = new ArrayList<>();
            for (String id : mappings.get(m).lgIds()) {
                for (int i = 0; i < goals.size(); i++) {
                    if (goals.get(i).id().equals(id)) nums.add(i + 1);
                }
            }
            if (nums.isEmpty()) continue;
            Collections.sort(nums);
            slots.add(new EvaluateSlot(mappings.get(m).method(), nums, base + (m < remainder ? 1 : 0)));
        }
        return slots;
    }

    private String buildEvaluateRules(List<EvaluateSlot> slots) {
        if (slots.isEmpty()) return "";
        var sb = new StringBuilder();
        sb.append("The user has mapped evaluation activities to learning goals. Each activity is ONE assessment that checks all of its goals together.\n\n");
        sb.append("CRITICAL INSTRUCTION FOR STEPS ARRAY FORMATTING:\n");
        sb.append("Return EXACTLY ").append(slots.size()).append(slots.size() == 1 ? " step" : " steps")
                .append(", in this order, by replacing <...> with ONE combined question or task:\n");
        for (var slot : slots) {
            sb.append("  \"").append(slot.minutes()).append(" min - ").append(slot.label())
                    .append(": <one question or task that checks ")
                    .append(slot.goalNumbers().size() == 1 ? "this goal" : "all of these goals together")
                    .append(">\"\n");
        }
        sb.append("Keep each prefix exactly as given. Do NOT split a step into several questions, and do NOT add any other steps.\n");
        return sb.toString();
    }

    private static final Pattern EVALUATE_STEP = Pattern.compile(
            "^(?:\\d+\\s*(?:min|m)[\\s—:-]*)?(?:(?:Prompt\\s+)?(?:LG|Goal)s?\\s*([\\d\\s&,and]+))?\\s*(?:[·•\\-]\\s*\\[?[^\\]:]*\\]?)?:?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Rebuilds the Check Understanding steps so there is exactly one per mapped activity,
     * whatever the LLM returned: questions written for the same activity are joined into one.
     */
    static ActivityBlockDto normalizeEvaluateSteps(ActivityBlockDto block, List<EvaluateSlot> slots) {
        if (block == null || slots.isEmpty()) return block;
        List<String> allSteps = block.sections() == null ? List.of() : block.sections().stream()
                .flatMap(s -> s.steps() == null ? Stream.<String>empty() : s.steps().stream())
                .collect(Collectors.toList());

        List<List<String>> questions = new ArrayList<>();
        slots.forEach(s -> questions.add(new ArrayList<>()));
        List<String> unmatched = new ArrayList<>();
        for (String step : allSteps) {
            var m = EVALUATE_STEP.matcher(step.trim());
            List<Integer> nums = new ArrayList<>();
            String question = step.trim();
            if (m.matches()) {
                if (m.group(1) != null) {
                    var digits = Pattern.compile("\\d+").matcher(m.group(1));
                    while (digits.find()) nums.add(Integer.parseInt(digits.group()));
                }
                question = m.group(2).trim();
            }
            if (question.isEmpty()) continue;
            int target = -1;
            for (int i = 0; i < slots.size() && target < 0; i++) {
                for (int n : nums) {
                    if (slots.get(i).goalNumbers().contains(n)) { target = i; break; }
                }
            }
            if (target >= 0) questions.get(target).add(question); else unmatched.add(question);
        }
        // Questions without a goal number fill boxes that got nothing, in order
        for (int i = 0; i < slots.size() && !unmatched.isEmpty(); i++) {
            if (questions.get(i).isEmpty()) questions.get(i).add(unmatched.remove(0));
        }

        List<String> steps = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            String question = questions.get(i).isEmpty()
                    ? "Question to be added"
                    : String.join(" ", questions.get(i));
            steps.add(slot.minutes() + " min - " + slot.label() + ": " + question);
        }
        int total = slots.stream().mapToInt(EvaluateSlot::minutes).sum();
        String title = block.sections() != null && !block.sections().isEmpty() && block.sections().get(0).title() != null
                ? block.sections().get(0).title() : "Check Understanding";
        List<String> methods = slots.stream().map(EvaluateSlot::method).distinct().collect(Collectors.toList());
        return new ActivityBlockDto(block.blockId(), block.phase(), block.phaseLabel(), block.goalTag(),
                block.objective(), block.description(), methods, block.materials(),
                List.of(new ActivitySectionDto(title, total, steps, List.of(), List.of())), total);
    }

    /** Splits the newline-separated materials string sent by the client into clean entries. */
    static List<String> parseMaterials(String availableMaterials) {
        if (availableMaterials == null || availableMaterials.isBlank()) return List.of();
        return Arrays.stream(availableMaterials.split("\\R"))
                .map(String::trim)
                .filter(m -> !m.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Drops any method or material the LLM returned that the user did not select,
     * so the initial timetable only shows the user's own choices.
     * Administrative and non-instructional phases (ARRIVE, BREAK, BUFFER, SUMMARY) never have activities.
     * A null {@code selectedActivities} means activities are unrestricted.
     */
    static ActivityBlockDto restrictToSelections(ActivityBlockDto block, List<String> selectedActivities, List<String> materials) {
        if (block == null) return null;
        boolean noActivities = isNoActivityPhase(block.phase());
        List<ActivitySectionDto> sections = block.sections() == null ? null : block.sections().stream()
                .map(s -> new ActivitySectionDto(s.title(), s.duration(), s.steps(),
                        noActivities ? List.of() : (selectedActivities == null ? s.methods() : keepSelected(s.methods(), selectedActivities)),
                        keepSelected(s.materials(), materials)))
                .collect(Collectors.toList());
        return new ActivityBlockDto(block.blockId(), block.phase(), block.phaseLabel(), block.goalTag(),
                block.objective(), block.description(),
                noActivities ? List.of() : (selectedActivities == null ? block.methods() : keepSelected(block.methods(), selectedActivities)),
                keepSelected(block.materials(), materials),
                sections, block.duration());
    }

    static boolean isNoActivityPhase(String phase) {
        if (phase == null) return false;
        String p = phase.trim().toUpperCase();
        return p.equals("ARRIVE") || p.equals("BREAK") || p.equals("BUFFER") || p.equals("SUMMARY");
    }

    private static List<String> keepSelected(List<String> values, List<String> allowed) {
        if (values == null) return null;
        return values.stream()
                .map(v -> matchSelection(v, allowed))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /** Returns the user's own spelling of {@code value} if it is one of {@code allowed} (case-insensitive), else null. */
    private static String matchSelection(String value, List<String> allowed) {
        if (value == null || allowed == null) return null;
        String v = value.trim();
        return allowed.stream().filter(a -> a != null && a.trim().equalsIgnoreCase(v)).findFirst().orElse(null);
    }

    /** The taxonomy best-fit activity for a learning cycle block's goal, or null for any other block. */
    static String learningCycleActivity(SkeletonBlockDto block, List<LearningGoalPlanDto> goals, List<String> selectedActivities) {
        if (!"LEARNING_CYCLE".equalsIgnoreCase(block.phase()) || goals == null) return null;
        int idx = block.lgIndex() - 1;
        if (idx < 0 || idx >= goals.size()) return null;
        return ActivityRecommender.recommend(goals.get(idx), selectedActivities);
    }

    private String buildLearningCycleRules(String activity) {
        return "REQUIRED ACTIVITY FOR THIS BLOCK: \"" + activity + "\".\n"
                + "It is the best fit for this learning goal's Bloom's/SOLO level among the user's activities. "
                + "The practice section MUST be built around this activity, no other activity type may appear in the steps, "
                + "and the 'methods' array MUST be exactly [\"" + activity + "\"].\n";
    }

    /** Forces a block's methods to the one required activity, wherever the LLM put them. */
    static ActivityBlockDto withSingleMethod(ActivityBlockDto block, String activity) {
        if (block == null) return null;
        List<ActivitySectionDto> sections = block.sections() == null ? null : block.sections().stream()
                .map(s -> new ActivitySectionDto(s.title(), s.duration(), s.steps(),
                        s.methods() == null || s.methods().isEmpty() ? s.methods() : List.of(activity),
                        s.materials()))
                .collect(Collectors.toList());
        return new ActivityBlockDto(block.blockId(), block.phase(), block.phaseLabel(), block.goalTag(),
                block.objective(), block.description(), List.of(activity), block.materials(),
                sections, block.duration());
    }
}
