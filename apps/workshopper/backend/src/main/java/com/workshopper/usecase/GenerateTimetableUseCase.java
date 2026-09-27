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
                    .append("treat its suggested activity as a starting point for that goal's Process/Practice activity, adapted to fit the session's selected activity types and materials.\n\n");
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
                    skeleton.learningGoal(), filteredBlocks,
                    skeleton.omittedGoalIndices(), skeleton.sessionId());
                    
            String skeletonBlocksStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(filteredSkeleton.blocks());

            for (SkeletonBlockDto block : filteredBlocks) {
                // Prepare stringified values for LLM
                String targetBlockStr = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(block);
                String selectedActivitiesStr = input.selectedActivities() != null && !input.selectedActivities().isEmpty() ? String.join(", ", input.selectedActivities()) : "NONE";
                
                Future<ActivityBlockDto> future = llmExecutor.submit(() -> {
                    String rules = buildEvaluateRules(block, goals, input);
                    return llm.hydrateActivityBlock(block, filteredSkeleton, goals, input, sessionTypeLabel, selectedActivitiesStr, goalsString, skeletonBlocksStr, targetBlockStr, rules, availableMaterials);
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


        return new WorkshopSessionDto(null, 
                java.util.UUID.randomUUID().toString(),
                title,
                input.learningGoals() != null && !input.learningGoals().isEmpty() ? input.learningGoals().get(0) : "",
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
            evaluateMappings = new java.util.ArrayList<>();
            for (int i = 0; i < goals.size(); i++) {
                var g = goals.get(i);
                String method = (g.assessActivities() != null && !g.assessActivities().isEmpty())
                        ? g.assessActivities().get(0)
                        : (meta.selectedActivities() != null && !meta.selectedActivities().isEmpty()
                                ? meta.selectedActivities().get(i % meta.selectedActivities().size())
                                : "Quiz"); // last-resort only, no longer the default path
                evaluateMappings.add(new com.workshopper.dto.EvaluateMappingDto(method, java.util.List.of(g.id())));
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
}
