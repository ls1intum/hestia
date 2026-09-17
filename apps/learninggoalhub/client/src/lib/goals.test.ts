import { describe, expect, it } from "vitest";
import type { GoalSource, LearningGoal } from "../api/client.ts";
import {
  buildCompetencyForest,
  coverageCounts,
  coverageOf,
  levelFlags,
} from "./goals.ts";

type Kind = GoalSource["documentKind"];

let nextId = 1;

/** A goal with one source per given document kind, hung under `parent` when one is given. */
function goal(
  fields: Partial<LearningGoal> & { kinds?: Kind[]; parent?: LearningGoal },
): LearningGoal {
  const { kinds = [], parent, ...rest } = fields;
  return {
    id: nextId++,
    text: "goal",
    origin: "EXTRACTED",
    role: "SKILL",
    sources: kinds.map((documentKind) => ({ documentKind }) as GoalSource),
    relationships: parent
      ? [{ type: "CONTRIBUTES_TO", targetGoalId: parent.id, targetText: "", confidence: 1, origin: "SYNTHESIS" }]
      : [],
    ...rest,
  } as LearningGoal;
}

const topic = () => goal({ origin: "TERMINAL", role: undefined, text: "topic" });

describe("coverageOf", () => {
  it("reads lecture, exercise and both off the sources' document kinds", () => {
    expect(coverageOf(goal({ kinds: ["LECTURE"] }))).toBe("lecture");
    expect(coverageOf(goal({ kinds: ["EXERCISE", "EXERCISE"] }))).toBe("exercise");
    expect(coverageOf(goal({ kinds: ["LECTURE", "EXERCISE"] }))).toBe("both");
  });

  it("is unknown for sources without a kind and null without any source", () => {
    expect(coverageOf(goal({ kinds: [undefined] }))).toBe("unknown");
    expect(coverageOf(goal({ kinds: [undefined, "LECTURE"] }))).toBe("lecture");
    expect(coverageOf(goal({}))).toBeNull();
  });
});

describe("coverageCounts", () => {
  it("rolls sub-skills up to their skill and topic, ignoring knowledge and source-less nodes", () => {
    const root = topic();
    const capability = goal({ origin: "SYNTHESIZED", parent: root });
    const goals = [
      root,
      capability,
      goal({ kinds: ["LECTURE"], parent: capability }),
      goal({ kinds: ["LECTURE", "EXERCISE"], parent: capability }),
      goal({ kinds: ["EXERCISE"], parent: root }),
      goal({ kinds: [undefined], parent: root }),
    ];
    const knowledgeParent = goals[2];
    goals.push(goal({ role: "KNOWLEDGE", kinds: ["EXERCISE"], parent: knowledgeParent }));

    const [tree] = buildCompetencyForest(goals);

    expect(coverageCounts(tree)).toEqual({
      lecture: 1,
      exercise: 1,
      both: 1,
      unknown: 1,
      total: 4,
      practised: 2,
    });
    const skill = tree.children.find((child) => child.role === "capability")!;
    expect(coverageCounts(skill)).toMatchObject({ total: 2, practised: 1, lecture: 1, both: 1 });
  });
});

describe("levelFlags", () => {
  const forestOf = (...children: Partial<LearningGoal & { kinds: Kind[] }>[]) => {
    const root = topic();
    const goals = [root, ...children.map((child) => goal({ ...child, parent: root }))];
    return { forest: buildCompetencyForest(goals), goals };
  };

  it("does not flag a lecture at UNDERSTAND practised at APPLY", () => {
    const { forest } = forestOf(
      { kinds: ["LECTURE"], bloomLevel: "UNDERSTAND" },
      { kinds: ["EXERCISE"], bloomLevel: "APPLY" },
    );
    expect(levelFlags(forest).size).toBe(0);
  });

  it("flags an exercise below UNDERSTAND under lecture sub-skills at APPLY or above", () => {
    const { forest, goals } = forestOf(
      { kinds: ["LECTURE"], bloomLevel: "ANALYZE" },
      { kinds: ["EXERCISE"], bloomLevel: "REMEMBER" },
    );
    const flags = levelFlags(forest);
    expect([...flags.keys()]).toEqual([goals[2].id]);
    expect(flags.get(goals[2].id!)).toContain("Remember");
  });

  it("flags an exercise at EVALUATE or above when the topic's lectures never pass UNDERSTAND", () => {
    const { forest, goals } = forestOf(
      { kinds: ["LECTURE"], bloomLevel: "UNDERSTAND" },
      { kinds: ["EXERCISE"], bloomLevel: "EVALUATE" },
      { kinds: ["EXERCISE"], bloomLevel: "ANALYZE" },
    );
    expect([...levelFlags(forest).keys()]).toEqual([goals[2].id]);
  });

  it("flags nothing without lecture outcomes to compare against", () => {
    const { forest } = forestOf(
      { kinds: ["EXERCISE"], bloomLevel: "CREATE" },
      { kinds: ["EXERCISE"], bloomLevel: "REMEMBER" },
      { kinds: [undefined], bloomLevel: "APPLY" },
    );
    expect(levelFlags(forest).size).toBe(0);
  });
});
