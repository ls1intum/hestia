Session type: {sessionType}
Student background: {studentBackground}

Learning goal text to review:
"{goal}"
{subSkillsContext}

Task:
FIRST, check if the text contains TWO OR MORE distinct learning goals in a single entry. This happens when:
- The text contains newlines or line breaks between complete thoughts
- Multiple complete goal sentences are separated by a period (e.g. "Students will apply X. Students will also evaluate Y")
- The word "and" joins ENTIRELY DIFFERENT skills or competencies
- A comma separates distinct measurable outcomes
In any of these cases, you MUST return a "split" suggestion with ALL distinct goals clearly stated in the "values" array.

THEN, if the text is a single goal:
1. If the goal contains MULTIPLE distinct competencies in one sentence, suggest splitting it into separate goals.
2. If the goal is vague, unmeasurable, or not phrased as an observable outcome, suggest a refined version.
3. CRITICAL RULE: There must NEVER be more than one SOLO/Bloom taxonomy verb in a single learning goal. For example, "explain concept A and concept B" is allowed, but "discuss and explain concept A" is strictly forbidden. If a goal uses multiple verbs, split it into separate goals or simplify it to one core verb.
4. If the goal is already clear, well-formed, and strictly uses only one core taxonomy verb, return an EMPTY array [].

Return a JSON array with at most 2 suggestions. Each suggestion must follow this schema:
[
  \{
    "type": "split" | "refine",
    "values": ["first goal text", "second goal text", "etc (only for split, always include ALL split goals)"],
    "message": "Short explanation of why this suggestion is helpful (max 1 sentence)"
  \}
]
IMPORTANT: For a "split" suggestion, "values" MUST contain an array of ALL separated goal strings.