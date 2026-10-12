import { describe, expect, it } from "vitest";
import { blockContent, blockMethods, stripStepTime } from "./block-content";
import type { ActivityBlock, ActivitySection } from "./workshop-generator";

const section = (title: string, steps: string[], methods: string[] = []): ActivitySection =>
  ({ title, duration: 0, steps, methods, materials: [] });

const block = (phase: string, sections: ActivitySection[], extra: Partial<ActivityBlock> = {}): ActivityBlock => ({
  phase, phaseLabel: phase, goalTag: "", objective: "", description: "", methods: [], materials: [],
  duration: 10, sections, ...extra,
});

describe("stripStepTime", () => {
  it("removes the minutes prefix in its usual spellings", () => {
    expect(stripStepTime("5 min - Prompt: Why?")).toBe("Prompt: Why?");
    expect(stripStepTime("10m: Discuss")).toBe("Discuss");
    expect(stripStepTime("3 min — Explain entropy")).toBe("Explain entropy");
    expect(stripStepTime("No time here")).toBe("No time here");
  });
});

describe("blockMethods", () => {
  it("collects block and section activities without lectures and duplicates", () => {
    const b = block("LEARNING_CYCLE", [section("Practice", [], ["Quiz", "Lecture"])], { methods: ["Quiz / Polls", "Presentation"] });
    expect(blockMethods(b)).toEqual(["Quiz / Polls"]); // "Quiz" is contained in "Quiz / Polls"
  });
});

describe("blockContent", () => {
  it("Arrive lists the session's goals, linked to the step that mentions them", () => {
    const b = block("ARRIVE", [section("Welcome", ["5 min - Welcome and present: Explain entropy"])]);
    const content = blockContent(b, ["Explain entropy", "Apply decision trees"]);
    expect(content).toEqual({
      kind: "goals",
      goals: [
        { step: "5 min - Welcome and present: Explain entropy", text: "Explain entropy" },
        { step: undefined, text: "Apply decision trees" },
      ],
    });
  });

  it("Arrive falls back to goals written in the steps when the session has none", () => {
    const b = block("ARRIVE", [section("Welcome", ["2 min - Learning goal: Explain entropy", "3 min - Say hello"])]);
    expect(blockContent(b, [])).toEqual({ kind: "goals", goals: [{ step: "2 min - Learning goal: Explain entropy", text: "Explain entropy" }] });
  });

  it("Activate shows the prompt step without its prefixes", () => {
    const b = block("ACTIVATE", [section("Activate", ["1 min - Settle in", "4 min - Prompt: What makes a good split?"])]);
    expect(blockContent(b, [])).toEqual({ kind: "prompt", prompt: { step: "4 min - Prompt: What makes a good split?", text: "What makes a good split?" } });
  });

  it("an empty prompt step still edits the step, showing the objective meanwhile", () => {
    const b = block("ACTIVATE", [section("Activate", ["5 min - Prompt: "])], { objective: "Recall trees" });
    expect(blockContent(b, [])).toEqual({ kind: "prompt", prompt: { step: "5 min - Prompt: ", text: "Recall trees" } });
  });

  it("a block without steps shows (and edits) its objective", () => {
    const b = block("ACTIVATE", [], { objective: "Recall trees" });
    expect(blockContent(b, [])).toEqual({ kind: "prompt", prompt: { objective: true, text: "Recall trees" } });
  });

  it("a learning cycle splits the content to teach from the activity", () => {
    const b = block("LEARNING_CYCLE", [
      section("You explain", ["3 min - Explain entropy", "2 min - Explain: Show an example"]),
      section("Participants Practice", ["2 min - Think: pick the best split", "3 min - Pair discussion: compare"]),
    ]);
    const content = blockContent(b, []);
    expect(content.kind).toBe("cycle");
    if (content.kind !== "cycle") return;
    // "Explain" as a verb stays; only an "Explain:" label is removed
    expect(content.content.map(c => c.text)).toEqual(["Explain entropy", "Show an example"]);
    expect(content.activity).toEqual({ step: "3 min - Pair discussion: compare", text: "Pair discussion: compare" });
  });

  it("a summary's activity step is not listed again as a takeaway", () => {
    const b = block("SUMMARY", [section("Wrap-up", ["2 min - Review key concepts", "1 min - Address final questions"])]);
    const content = blockContent(b, []);
    expect(content.kind).toBe("summary");
    if (content.kind !== "summary") return;
    expect(content.takeaways.map(t => t.text)).toEqual(["Review key concepts"]);
    expect(content.activity.text).toBe("Address final questions");
  });

  it("evaluate blocks pass all their steps on; other phases have no special layout", () => {
    const steps = ["5 min - Goal 1 - Quiz: What is entropy?", "5 min - Goal 2 - Debate: Trees or forests?"];
    expect(blockContent(block("EVALUATE", [section("Check", steps)]), [])).toEqual({ kind: "evaluate", steps });
    expect(blockContent(block("BREAK", [section("Break", ["10 min - Break"])]), [])).toEqual({ kind: "other" });
    expect(blockContent(block("CUSTOM", []), [])).toEqual({ kind: "other" });
  });
});
