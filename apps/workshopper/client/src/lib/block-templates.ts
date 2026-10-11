import { ActivityBlock, LearningGoalPlan } from "@/lib/workshop-generator";
import { defaultEvaluateMappings } from "@/lib/activity-taxonomy";

/**
 * Block types a user can add by hand in the timetable step. Each template mirrors the
 * shape of a generated block of that phase (sections, step prefixes), so the row
 * renders with the same layout and emoji and the user only fills in the content.
 */
export const ADDABLE_BLOCK_TYPES: { phase: string; label: string }[] = [
  { phase: "ARRIVE", label: "Arrive & Welcome" },
  { phase: "ACTIVATE", label: "Activate Knowledge" },
  { phase: "LEARNING_CYCLE", label: "Learning Cycle" },
  { phase: "EVALUATE", label: "Check Understanding" },
  { phase: "SUMMARY", label: "Summary & Wrap-up" },
  { phase: "BREAK", label: "Break" },
  { phase: "BUFFER", label: "Buffer" },
];

const emptyBlock = (phase: string, phaseLabel: string, duration: number): ActivityBlock => ({
  phase,
  phaseLabel,
  goalTag: "",
  objective: "",
  description: "",
  methods: [],
  materials: [],
  duration,
});

export function createBlockFromTemplate(
  phase: string,
  goals: LearningGoalPlan[],
  selectedActivities: string[],
): ActivityBlock {
  switch (phase) {
    case "ARRIVE":
      return {
        ...emptyBlock(phase, "Arrive & Welcome", 5),
        sections: [{ title: "Welcome", duration: 5, steps: ["5 min - Welcome participants and present the learning goals"], methods: [], materials: [] }],
      };
    case "ACTIVATE":
      return {
        ...emptyBlock(phase, "Activate Knowledge", 5),
        sections: [{ title: "Activate", duration: 5, steps: ["5 min - Prompt: "], methods: [], materials: [] }],
      };
    case "LEARNING_CYCLE":
      return {
        ...emptyBlock(phase, "New Learning Cycle", 20),
        sections: [
          { title: "You explain", duration: 10, steps: ["10 min - "], methods: [], materials: [] },
          { title: "Participants Practice", duration: 10, steps: ["10 min - Activity: "], methods: [], materials: [] },
        ],
      };
    case "EVALUATE": {
      // One step per activity, each covering the goals whose Bloom's/SOLO level it fits best
      const mappings = defaultEvaluateMappings(goals, selectedActivities);
      const labelled = mappings.length > 0
        ? mappings.map(m => ({
            method: m.method,
            label: "Goal " + m.lgIds.map(id => goals.findIndex(g => g.id === id) + 1).join(" & "),
          }))
        : [{ method: selectedActivities[0] || "Quiz / Polls", label: "Goal 1" }];
      const perStep = Math.max(1, Math.floor(10 / labelled.length));
      const steps = labelled.map(l => `${perStep} min - ${l.label} - ${l.method}: Question to be added`);
      return {
        ...emptyBlock(phase, "Check Understanding", perStep * labelled.length),
        sections: [{ title: "Check Understanding", duration: perStep * labelled.length, steps, methods: [], materials: [] }],
      };
    }
    case "SUMMARY":
      return {
        ...emptyBlock(phase, "Summary & Wrap-up", 5),
        sections: [{ title: "Wrap-up", duration: 5, steps: ["3 min - ", "2 min - Prompt: "], methods: [], materials: [] }],
      };
    case "BREAK":
      return {
        ...emptyBlock(phase, "Break", 10),
        sections: [{ title: "Break", duration: 10, steps: ["10 min - Break"], methods: [], materials: [] }],
      };
    case "BUFFER":
      return {
        ...emptyBlock(phase, "Buffer", 5),
        sections: [{ title: "Buffer", duration: 5, steps: ["5 min - Buffer"], methods: [], materials: [] }],
      };
    default:
      return emptyBlock("CUSTOM", "Custom Block", 10);
  }
}
