import { describe, expect, it } from "vitest";
import { ADDABLE_PHASES, PHASES, createBlockFromTemplate, phaseInfo } from "./phases";
import type { LearningGoalPlan } from "./workshop-generator";

const goal = (id: string, bloomLevel?: string): LearningGoalPlan =>
  ({ id, goal: id, originalGoal: id, prerequisites: [], achieveActivities: [], assessActivities: [], bloomLevel } as LearningGoalPlan);

describe("phases", () => {
  it("every phase in the Add Block menu builds a block of that phase with its label", () => {
    for (const { phase } of ADDABLE_PHASES) {
      const b = createBlockFromTemplate(phase, [goal("g1")], ["Quiz / Polls"]);
      expect(b.phase).toBe(phase);
      expect(b.duration).toBeGreaterThan(0);
      // a hand-added block's sections add up to the block
      const sum = (b.sections || []).reduce((acc, s) => acc + (s.duration || 0), 0);
      expect(sum).toBe(b.duration);
    }
  });

  it("unknown phases are treated as custom blocks", () => {
    expect(phaseInfo("SOMETHING_OLD")).toBe(PHASES.CUSTOM);
    expect(phaseInfo(undefined)).toBe(PHASES.CUSTOM);
    expect(createBlockFromTemplate("CUSTOM", [], []).phase).toBe("CUSTOM");
  });

  it("an added understanding check has one question per mapped activity", () => {
    const b = createBlockFromTemplate("EVALUATE", [goal("g1", "remember"), goal("g2", "create")], ["Quiz / Polls", "Design Sprint / Prototype Challenge"]);
    expect(b.sections![0].steps!.length).toBeGreaterThanOrEqual(1);
    for (const step of b.sections![0].steps!) expect(step).toMatch(/^\d+ min - Goal [\d &]+ - .+: Question to be added$/);
  });

  it("breaks and buffers can't be expanded and buffers get no slides", () => {
    expect(PHASES.BREAK.expandable).toBe(false);
    expect(PHASES.BUFFER.expandable).toBe(false);
    expect(PHASES.BUFFER.hasSlides).toBe(false);
  });
});
