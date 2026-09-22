package com.workshopper.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmTemplatesTest {

    @Test
    void testAllTemplatesRenderWithoutMissingVariables() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:prompts/*.st");
        assertTrue(resources.length > 0, "Should find prompt templates");

        Map<String, Object> dummyData = new HashMap<>();
        // Workshop details
        dummyData.put("sessionType", "Workshop");
        dummyData.put("duration", 90);
        dummyData.put("participants", 20);
        dummyData.put("studentBackground", "Beginners");
        dummyData.put("learningGoals", "- LG1\n- LG2");
        dummyData.put("sourceDocument", "Some text...");
        dummyData.put("interactionLevel", "High");
        
        // Goals
        dummyData.put("goal", "Learn Spring");
        dummyData.put("goals", "1. Learn Spring\n2. Learn Java");
        dummyData.put("subSkillsContext", "- sub1\n- sub2");
        dummyData.put("document", "Some document text...");
        
        // Block specifics
        dummyData.put("skeleton", "Skeleton data");
        dummyData.put("selectedActivities", "Quiz");
        dummyData.put("skeletonBlocks", "[{}]");
        dummyData.put("targetBlock", "{}");
        dummyData.put("evaluateRules", "Rule 1");
        
        // PPTX Slide specifics
        dummyData.put("blockLabel", "Phase 1");
        dummyData.put("blockObjective", "Learn basics");
        dummyData.put("sectionSteps", "Step 1\nStep 2");
        dummyData.put("materials", "Slides");
        dummyData.put("densityProfile", "Low");
        dummyData.put("slideCountLimit", 3);
        dummyData.put("phaseTitle", "Welcome");
        dummyData.put("targetGoals", "Goal 1");
        dummyData.put("goalsList", "Goal 1");
        dummyData.put("methods", "Quiz");
        dummyData.put("objective", "Objective");
        dummyData.put("schemaSnippet", "{}");

        // Materials
        dummyData.put("availableMaterials", "Whiteboard and markers");

        for (Resource resource : resources) {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            PromptTemplate template = new PromptTemplate(content);
            
            assertDoesNotThrow(() -> {
                String rendered = template.render(dummyData);
                // PromptTemplate.render() uses StringSubstitutor. It might not throw, so we check for leftover {vars}
                assertFalse(rendered.matches(".*\\{.+}.*"), "Rendered template " + resource.getFilename() + " contains unreplaced variables: " + rendered);
                
                if (resource.getFilename().equals("generate-timetable-block-user.st")) {
                    assertTrue(rendered.contains("Whiteboard and markers"), "Rendered timetable block must contain the provided materials string");
                }
            }, "Template " + resource.getFilename() + " failed to render");
        }
    }
}
