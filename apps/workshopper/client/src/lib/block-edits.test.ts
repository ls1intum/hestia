import { describe, expect, it } from "vitest";
import { addStep, deleteStep, removeActivity, setBlockMinutes, setStepMinutes, setStepText } from "./block-edits";
import type { ActivityBlock } from "./workshop-generator";

const cycle = (): ActivityBlock => ({
  phase: "LEARNING_CYCLE", phaseLabel: "Trees", goalTag: "", objective: "", description: "",
  methods: ["Think-Pair-Share"], materials: [], duration: 15,
  sections: [
    { title: "You explain", duration: 5, steps: ["2 min - Define entropy", "3 min - Show the formula"], methods: [], materials: [] },
    { title: "Participants Practice", duration: 10, steps: ["10 min - Activity: Pick a split"], methods: ["Think-Pair-Share"], materials: [] },
  ],
});

describe("setStepText", () => {
  it("keeps the step's minutes when the new text has none", () => {
    const b = setStepText(cycle(), 0, 1, "Derive the formula");
    expect(b.sections![0].steps).toEqual(["2 min - Define entropy", "3 min - Derive the formula"]);
  });

  it("takes the text as is when it brings its own minutes", () => {
    expect(setStepText(cycle(), 0, 0, "4 min - Define it").sections![0].steps![0]).toBe("4 min - Define it");
  });

  it("does not change the original block", () => {
    const original = cycle();
    setStepText(original, 0, 0, "x");
    expect(original.sections![0].steps![0]).toBe("2 min - Define entropy");
  });
});

describe("setStepMinutes", () => {
  it("updates the step, its section and the block", () => {
    const b = setStepMinutes(cycle(), 0, 1, "7");
    expect(b.sections![0].steps![1]).toBe("7 min - Show the formula");
    expect(b.sections![0].duration).toBe(9);
    expect(b.duration).toBe(19);
  });

  it("treats invalid or negative minutes as 0", () => {
    expect(setStepMinutes(cycle(), 0, 0, "abc").sections![0].steps![0]).toBe("0 min - Define entropy");
    expect(setStepMinutes(cycle(), 0, 0, "-5").sections![0].steps![0]).toBe("0 min - Define entropy");
  });
});

describe("setBlockMinutes", () => {
  it("sets the block; a single section takes the same minutes", () => {
    const single: ActivityBlock = { ...cycle(), sections: [cycle().sections![0]] };
    const b = setBlockMinutes(single, "12");
    expect(b.duration).toBe(12);
    expect(b.sections![0].duration).toBe(12);
  });

  it("leaves several sections alone", () => {
    const b = setBlockMinutes(cycle(), "30");
    expect(b.duration).toBe(30);
    expect(b.sections!.map(s => s.duration)).toEqual([5, 10]);
  });
});

describe("addStep / deleteStep", () => {
  it("adds a 5-minute step to the last section", () => {
    const b = addStep(cycle(), "Share answers");
    expect(b.sections![1].steps).toEqual(["10 min - Activity: Pick a split", "5 min - Share answers"]);
    expect(b.sections![1].duration).toBe(15);
    expect(b.duration).toBe(20);
  });

  it("creates a section for a block without one", () => {
    const b = addStep({ ...cycle(), sections: [] }, "First step");
    expect(b.sections).toEqual([{ title: "", duration: 5, steps: ["5 min - First step"], methods: [], materials: [] }]);
    expect(b.duration).toBe(5);
  });

  it("deleting a step updates the times", () => {
    const b = deleteStep(cycle(), 0, 0);
    expect(b.sections![0].steps).toEqual(["3 min - Show the formula"]);
    expect(b.duration).toBe(13);
  });
});

describe("removeActivity", () => {
  it("removes it from the block and its sections, keeping the steps", () => {
    const b = removeActivity(cycle(), "Think-Pair-Share");
    expect(b.methods).toEqual([]);
    expect(b.sections![1].methods).toEqual([]);
    expect(b.sections![1].steps).toEqual(["10 min - Activity: Pick a split"]);
  });
});
