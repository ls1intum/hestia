SESSION CONTEXT:
- Type: {sessionType}
- Total duration: {duration} minutes
- Participants: {participants}
- Interaction level: {interactionLevel}
- Student background: {studentBackground}

SELECTED ACTIVITIES (permitted methods): {selectedActivities}
AVAILABLE MATERIALS (physical tools/spaces): {availableMaterials}

LEARNING GOALS:
{learningGoals}

FULL SESSION OUTLINE (For context only):
{skeletonBlocks}

TARGET BLOCK TO HYDRATE:
{targetBlock}

{evaluateRules}

Return the hydrated block as a JSON object matching the ActivityBlockDto schema.
It must include a 'sections' array, where each section contains a 'steps' array.
Each step should be a string in the format "X min - Description".
The total duration of steps within a section must equal the section's duration.
Ensure you use the exact provided learning goals where appropriate.
CRITICAL FOR 'phaseLabel': Ensure 'phaseLabel' is a concise, meaningful title for this specific block (MAX 5 WORDS, e.g. "Intro to Logistic Regression"). Do NOT just say "Learning Cycle" and do NOT repeat the entire learning goal.
CRITICAL FOR 'methods': You MUST pick EXACTLY ONE method from the SELECTED ACTIVITIES list. The 'methods' array MUST contain ONLY 1 item (or 0). NEVER output 2 or more items.
