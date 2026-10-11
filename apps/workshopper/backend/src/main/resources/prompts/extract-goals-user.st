Session type: {sessionType}
Student background: {studentBackground}

Document text:
---
{document}
---

Task:
Extract all distinct learning goals or intended learning outcomes from this document.
- Include goals stated explicitly (e.g. "After this session, students will be able to...")
- Include goals implied by section headings or topic lists
- Rephrase each as a measurable outcome using a taxonomy verb.
- CRITICAL RULE: There must NEVER be more than one SOLO/Bloom taxonomy verb in a single learning goal. For example, "explain concept A and concept B" is allowed, but "discuss and explain concept A" is strictly forbidden. Use exactly one core measurable verb per goal.
- Return between 2 and 8 goals
- Each goal must be a complete sentence starting directly with the action verb (e.g. "apply X"). Do NOT include "Participants will be able to"

Return a JSON array of strings, e.g.:
["apply X...", "evaluate Y..."]