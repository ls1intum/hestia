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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class GenerateTimetableUseCase {

    private static final Logger log = LoggerFactory.getLogger(GenerateTimetableUseCase.class);
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

        
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

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
                                .collect(java.util.stream.Collectors.toList());
                        return new SkeletonBlockDto(
                                block.phase(), block.lgIndex(), block.duration(),
                                block.title(), block.description(), filteredSections);
                    })
                    .collect(java.util.stream.Collectors.toList());
                    
            SessionSkeletonDto filteredSkeleton = new SessionSkeletonDto(
                    filteredBlocks,
                    skeleton.omittedGoalIndices(), skeleton.sessionId());
                    
            String skeletonBlocksStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(filteredSkeleton.blocks());

            for (SkeletonBlockDto block : filteredBlocks) {
                // Prepare stringified values for LLM
                String targetBlockStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(block);
                String selectedActivitiesStr = activitiesRestricted ? String.join(", ", input.selectedActivities()) : "ANY";
                
                String cycleActivity = learningCycleActivity(block, goals, input.selectedActivities());

                Future<ActivityBlockDto> future = llmExecutor.submit(() -> {
                    String rules = cycleActivity != null
                            ? buildLearningCycleRules(cycleActivity)
                            : buildEvaluateRules(block, goals, input);
                    ActivityBlockDto hydrated = llm.hydrateActivityBlock(block, filteredSkeleton, goals, input, sessionTypeLabel, selectedActivitiesStr, goalsString, skeletonBlocksStr, targetBlockStr, rules, materialsStr);
                    ActivityBlockDto restricted = restrictToSelections(hydrated, allowedActivities, materials);
                    return cycleActivity != null ? withSingleMethod(restricted, cycleActivity) : restricted;
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
        
        String title = llm.generateSessionTitle(skeleton, goals, input, sessionTypeLabel, skeletonBlocksStr, goalsString).trim();


        return new WorkshopSessionDto(
                java.util.UUID.randomUUID().toString(),
                title,
                input.studentBackground(),
                generatedBlocks,
                null,
                null
        );
    }

    
    private String buildEvaluateRules(SkeletonBlockDto targetBlock, List<LearningGoalPlanDto> goals, WorkshopInputDto meta) {
        if (!"EVALUATE".equalsIgnoreCase(targetBlock.phase())) return "";
        int totalEvalMin = targetBlock.duration() > 0 ? targetBlock.duration() : 10;
        
        var evaluateMappingStr = new StringBuilder();
        var evaluateMappings = meta.evaluateMappings();

        if ((evaluateMappings == null || evaluateMappings.isEmpty()) && goals != null && !goals.isEmpty()) {
            // Goals that end up with the same activity share one assessment, like a user-made mapping
            Map<String, List<String>> goalsByMethod = new java.util.LinkedHashMap<>();
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
            evaluateMappings = new java.util.ArrayList<>();
            for (var e : goalsByMethod.entrySet()) {
                evaluateMappings.add(new com.workshopper.dto.EvaluateMappingDto(e.getKey(), e.getValue()));
            }
        }

        if (evaluateMappings != null && !evaluateMappings.isEmpty()) {
            evaluateMappingStr.append("The user has explicitly requested the following mapping of evaluation methods to learning goals:\n");
            
            int timePerActivity = Math.max(1, totalEvalMin / evaluateMappings.size());
            
            for (var mapping : evaluateMappings) {
                String lgLabels = mapping.lgIds().stream().map(id -> {
                    for (int i = 0; i < goals.size(); i++) {
                        if (goals.get(i).id().equals(id)) return "Goal " + (i + 1);
                    }
                    return id;
                }).collect(java.util.stream.Collectors.joining(", "));
                
                evaluateMappingStr.append(String.format(" - Use '%s' to evaluate %s (approx %dm)\n", 
                        mapping.method(), lgLabels, timePerActivity));
            }
            
            evaluateMappingStr.append("\nCRITICAL INSTRUCTION FOR STEPS ARRAY FORMATTING:\n");
            evaluateMappingStr.append("You MUST create exactly ONE combined step for each activity mapping above.\n");
            evaluateMappingStr.append("Do NOT generate 4 separate steps if the user mapped 4 goals to 2 activities. You must generate exactly 2 steps!\n\n");
            evaluateMappingStr.append("Format each step string exactly like this to map it to the correct goal(s):\n");
            evaluateMappingStr.append("If evaluating a single goal:\n");
            evaluateMappingStr.append("  \"Goal 1 - Quiz: Describe the three components of MVC.\"\n");
            evaluateMappingStr.append("If evaluating multiple goals in one activity, combine their numbers:\n");
            evaluateMappingStr.append("  \"Goal 1 & 2 - Concept Mapping: Create a diagram relating MVC and REST APIs.\"\n");
            evaluateMappingStr.append("Do NOT split them into separate steps. Do NOT deviate from this prefix format.\n");
        }
        
        return evaluateMappingStr.toString();
    }

    /** Splits the newline-separated materials string sent by the client into clean entries. */
    static List<String> parseMaterials(String availableMaterials) {
        if (availableMaterials == null || availableMaterials.isBlank()) return List.of();
        return java.util.Arrays.stream(availableMaterials.split("\\R"))
                .map(String::trim)
                .filter(m -> !m.isEmpty())
                .distinct()
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Drops any method or material the LLM returned that the user did not select,
     * so the initial timetable only shows the user's own choices.
     * A null {@code selectedActivities} means activities are unrestricted.
     */
    static ActivityBlockDto restrictToSelections(ActivityBlockDto block, List<String> selectedActivities, List<String> materials) {
        if (block == null) return null;
        List<ActivitySectionDto> sections = block.sections() == null ? null : block.sections().stream()
                .map(s -> new ActivitySectionDto(s.title(), s.duration(), s.steps(),
                        selectedActivities == null ? s.methods() : keepSelected(s.methods(), selectedActivities),
                        keepSelected(s.materials(), materials)))
                .collect(java.util.stream.Collectors.toList());
        return new ActivityBlockDto(block.blockId(), block.phase(), block.phaseLabel(), block.goalTag(),
                block.objective(), block.description(),
                selectedActivities == null ? block.methods() : keepSelected(block.methods(), selectedActivities),
                keepSelected(block.materials(), materials),
                sections, block.duration());
    }

    private static List<String> keepSelected(List<String> values, List<String> allowed) {
        if (values == null) return null;
        return values.stream()
                .map(v -> matchSelection(v, allowed))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(java.util.stream.Collectors.toList());
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
                .collect(java.util.stream.Collectors.toList());
        return new ActivityBlockDto(block.blockId(), block.phase(), block.phaseLabel(), block.goalTag(),
                block.objective(), block.description(), List.of(activity), block.materials(),
                sections, block.duration());
    }
}
