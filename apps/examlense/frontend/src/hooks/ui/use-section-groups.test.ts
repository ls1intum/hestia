import { describe, it, expect } from "vitest";
import { computeSectionGroups, computeTaskLetters } from "./use-section-groups";
import { figureLabelsForBlocks, type Section, type SectionBlock, type Task } from "@/lib/exam/exam-helpers";

const section = (id: string, position: number, name = ""): Section =>
  ({ id, position, name }) as Section;

const task = (id: string, position: number, sectionId: string | null): Task =>
  ({ id, position, section_id: sectionId, created_at: `2024-01-0${position + 1}` }) as Task;

const figure = (id: string, position: number, sectionId: string): SectionBlock =>
  ({ id, position, section_id: sectionId, kind: "figure", created_at: "2024-01-01" }) as SectionBlock;

describe("computeSectionGroups", () => {
  const sections = [section("s2", 2), section("s1", 1), section("empty", 3)];
  const tasks = [
    task("t2", 1, "s1"),
    task("t1", 0, "s1"),
    task("t3", 0, "s2"),
    task("orphan", 0, null),
  ];
  const blocks = [figure("f1", 2, "s1")];

  it("orders sections by position and assigns index-based slugs", () => {
    const grouped = computeSectionGroups(sections, tasks, blocks, true);
    // s1 (pos 1) → section-1, s2 (pos 2) → section-2, empty (pos 3) → section-3, orphan last.
    expect(grouped.map((g) => g.slug)).toEqual([
      "section-1",
      "section-2",
      "section-3",
      "section-unassigned",
    ]);
    expect(grouped[0].section?.id).toBe("s1");
    expect(grouped[3].section).toBeNull();
  });

  it("includeEmpty:false drops sections with no items and keeps the orphan bucket", () => {
    const grouped = computeSectionGroups(sections, tasks, blocks, false);
    // "empty" section has no items → dropped; orphan has a task → kept.
    expect(grouped.map((g) => g.slug)).toEqual([
      "section-1",
      "section-2",
      "section-unassigned",
    ]);
  });

  it("interleaves tasks and blocks by position within a section", () => {
    const grouped = computeSectionGroups(sections, tasks, blocks, true);
    const s1 = grouped[0];
    // s1 items by position: t1 (0), t2 (1), f1 (2).
    expect(s1.items.map((it) => it.position)).toEqual([0, 1, 2]);
    expect(s1.items[2].kind).toBe("figure");
  });
});

describe("computeTaskLetters", () => {
  const sections = [section("s1", 1), section("s2", 2)];
  const tasks = [
    task("t2", 1, "s1"),
    task("t1", 0, "s1"),
    task("t3", 0, "s2"),
    task("orphan", 0, null),
  ];

  it("assigns per-section letter labels ordered by task position", () => {
    const grouped = computeSectionGroups(sections, tasks, [], true);
    const letters = computeTaskLetters(grouped);
    // s1 tasks by position: t1 (0) → a, t2 (1) → b.
    expect(letters.get("t1")).toBe("a");
    expect(letters.get("t2")).toBe("b");
    // First (only) task in s2 and in the orphan bucket → a.
    expect(letters.get("t3")).toBe("a");
    expect(letters.get("orphan")).toBe("a");
  });
});

describe("figureLabelsForBlocks (shared by useSectionGroups)", () => {
  it("labels figures as Figure {sectionNumber}.{index}", () => {
    const sections = [section("s1", 1)];
    const blocks = [figure("f1", 2, "s1")];
    expect(figureLabelsForBlocks(sections, blocks).get("f1")).toBe("Figure 1.1");
  });
});
