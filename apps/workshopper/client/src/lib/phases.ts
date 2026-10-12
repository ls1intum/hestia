import type { ActivityBlock, LearningGoalPlan } from "./workshop-generator";
import type { ActivityMode } from "./constants";
import { defaultEvaluateMappings } from "./activity-taxonomy";

/**
 * Everything the client knows about a block phase, in one place. To add a phase, add an entry
 * here (and its handling on the server, see docs/EXTENDING.md); the timetable, Final Review,
 * the Add Block menu and the slide workstation all read this table.
 */
export interface PhaseInfo {
  /** Default title of a block of this phase */
  label: string;
  emoji: string;
  /** Colour family of the block (MODE_COLORS in constants.ts) */
  mode: ActivityMode;
  /** Drawn as a smaller row on the Final Review timeline */
  compact: boolean;
  /** Has details to show when the block is expanded (timetable, Final Review) */
  expandable: boolean;
  /** Slides are generated for it in the Preparation step */
  hasSlides: boolean;
  /**
   * Builds a block of this phase added by hand in the timetable. Each template mirrors the
   * shape of a generated block (sections, step prefixes), so the row renders like a generated one.
   * Phases without a template are not offered in the Add Block menu.
   */
  template?: (goals: LearningGoalPlan[], selectedActivities: string[]) => ActivityBlock;
}

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

/** A block with one section holding the given steps; the section lasts as long as the block. */
const oneSection = (phase: string, label: string, duration: number, title: string, steps: string[]): ActivityBlock => ({
  ...emptyBlock(phase, label, duration),
  sections: [{ title, duration, steps, methods: [], materials: [] }],
});

export const PHASES: Record<string, PhaseInfo> = {
  ARRIVE: {
    label: "Arrive & Welcome", emoji: "👋", mode: "setup", compact: true, expandable: true, hasSlides: true,
    template: () => oneSection("ARRIVE", "Arrive & Welcome", 5, "Welcome", ["5 min - Welcome participants and present the learning goals"]),
  },
  ACTIVATE: {
    label: "Activate Knowledge", emoji: "💡", mode: "practice", compact: false, expandable: true, hasSlides: true,
    template: () => oneSection("ACTIVATE", "Activate Knowledge", 5, "Activate", ["5 min - Prompt: "]),
  },
  LEARNING_CYCLE: {
    label: "Learning Cycle", emoji: "🎯", mode: "practice", compact: false, expandable: true, hasSlides: true,
    template: () => ({
      ...emptyBlock("LEARNING_CYCLE", "New Learning Cycle", 20),
      sections: [
        { title: "You explain", duration: 10, steps: ["10 min - "], methods: [], materials: [] },
        { title: "Participants Practice", duration: 10, steps: ["10 min - Activity: "], methods: [], materials: [] },
      ],
    }),
  },
  EVALUATE: {
    label: "Check Understanding", emoji: "✅", mode: "evaluate", compact: false, expandable: true, hasSlides: true,
    template: (goals, selectedActivities) => {
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
      return oneSection("EVALUATE", "Check Understanding", perStep * labelled.length, "Check Understanding", steps);
    },
  },
  SUMMARY: {
    label: "Summary & Wrap-up", emoji: "🏁", mode: "summary", compact: false, expandable: true, hasSlides: true,
    template: () => oneSection("SUMMARY", "Summary & Wrap-up", 5, "Wrap-up", ["3 min - ", "2 min - Prompt: "]),
  },
  BREAK: {
    label: "Break", emoji: "☕", mode: "break", compact: true, expandable: false, hasSlides: true,
    template: () => oneSection("BREAK", "Break", 10, "Break", ["10 min - Break"]),
  },
  BUFFER: {
    label: "Buffer", emoji: "⏳", mode: "break", compact: true, expandable: false, hasSlides: false,
    template: () => oneSection("BUFFER", "Buffer", 5, "Buffer", ["5 min - Buffer"]),
  },
  /** Added by hand with "Custom Block"; also the fallback for unknown phases */
  CUSTOM: {
    label: "Custom Block", emoji: "✏️", mode: "practice", compact: false, expandable: true, hasSlides: false,
  },
};

/** The table entry of a phase; unknown phases are treated like custom blocks. */
export function phaseInfo(phase: string | undefined): PhaseInfo {
  return PHASES[(phase || "").toUpperCase()] ?? PHASES.CUSTOM;
}

/** Phases offered in the timetable's Add Block menu, in table order. */
export const ADDABLE_PHASES = Object.entries(PHASES)
  .filter(([, info]) => info.template)
  .map(([phase, info]) => ({ phase, label: info.label, emoji: info.emoji }));

/** A new block of the given phase for the timetable; a custom block for any phase without a template. */
export function createBlockFromTemplate(phase: string, goals: LearningGoalPlan[], selectedActivities: string[]): ActivityBlock {
  return PHASES[phase]?.template?.(goals, selectedActivities) ?? emptyBlock("CUSTOM", PHASES.CUSTOM.label, 10);
}
