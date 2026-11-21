import { describe, expect, it } from "vitest";
import { applySavedGoals, fixGrammarOfOwnGoals, goalInputs, isCoreGoalId, remapMappings } from "./core-goals";
import type { CourseGoal, LearningGoalPlan } from "./workshop-generator";

const CORE_ID = "3f2b8c1e-5a4d-4e6f-9a7b-1c2d3e4f5a6b";

const plan = (id: string, goal: string): LearningGoalPlan =>
  ({ id, goal, originalGoal: goal, prerequisites: [], achieveActivities: [], assessActivities: [] });

describe("core goal ids", () => {
  it("are UUIDs; temporary ids are not", () => {
    expect(isCoreGoalId(CORE_ID)).toBe(true);
    expect(isCoreGoalId("g0")).toBe(false);
    expect(isCoreGoalId("g1731000000-0")).toBe(false);
    expect(isCoreGoalId(undefined)).toBe(false);
  });
});

describe("saving the goals of the Goals step", () => {
  it("sends core goals by id and new ones without", () => {
    expect(goalInputs([plan(CORE_ID, "Known"), plan("g0", "New")]))
      .toEqual([{ id: CORE_ID, text: "Known" }, { text: "New", id: undefined }]);
  });

  it("takes id, text and levels from the saved core goals", () => {
    const saved: CourseGoal[] = [
      { id: CORE_ID, text: "Known", originalText: "Known (v1)", bloomLevel: "APPLY", status: "PENDING" },
      { id: "11111111-1111-4111-8111-111111111111", text: "New", status: "PENDING" },
    ];
    const { plans, idMap } = applySavedGoals([plan(CORE_ID, "Known"), plan("g0", "New")], saved);
    expect(plans.map(p => p.id)).toEqual([CORE_ID, "11111111-1111-4111-8111-111111111111"]);
    expect(plans[0].originalGoal).toBe("Known (v1)");
    expect(plans[0].bloomLevel).toBe("APPLY");
    expect(plans[1].originalGoal).toBe("New");
    expect(idMap).toEqual({ [CORE_ID]: CORE_ID, g0: "11111111-1111-4111-8111-111111111111" });
  });

  it("moves evaluate mappings to the new ids", () => {
    expect(remapMappings([{ method: "Quiz", lgIds: ["g0", "other"] }], { g0: CORE_ID }))
      .toEqual([{ method: "Quiz", lgIds: [CORE_ID, "other"] }]);
    expect(remapMappings(undefined, {})).toBeUndefined();
  });
});

describe("fixGrammarOfOwnGoals", () => {
  it("fixes only goals written in the session and keeps the course's wording", async () => {
    const seen: string[][] = [];
    const fix = async (texts: string[]) => { seen.push(texts); return texts.map(t => t.toUpperCase()); };

    const result = await fixGrammarOfOwnGoals(
      [{ id: CORE_ID, text: "course goal" }, { id: "g1", text: "own goal" }],
      fix,
    );

    expect(seen).toEqual([["own goal"]]);
    expect(result).toEqual(["course goal", "OWN GOAL"]);
  });

  it("does not call the fix when every goal comes from the course", async () => {
    let called = false;
    const result = await fixGrammarOfOwnGoals([{ id: CORE_ID, text: "course goal" }], async t => { called = true; return t; });

    expect(called).toBe(false);
    expect(result).toEqual(["course goal"]);
  });

  it("keeps the texts when the fix answers with a different number of goals", async () => {
    const result = await fixGrammarOfOwnGoals([{ id: "g1", text: "a" }, { id: "g2", text: "b" }], async () => ["only one"]);

    expect(result).toEqual(["a", "b"]);
  });
});
