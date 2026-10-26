// @vitest-environment jsdom
import "../../test/dom.ts";
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import type { LearningGoal } from "../../api/client.ts";
import type { TreeRole } from "../../lib/goals.ts";
import { ChildPreview } from "./DiagramRows.tsx";
import type { Row } from "./model.ts";

function row(id: number, role: TreeRole, shortLabel: string): Row {
  return { id, parent: 1, number: "", role, session: "", goal: { id, shortLabel } as LearningGoal };
}

describe("ChildPreview", () => {
  it("names each child's tier, not only its colour", () => {
    render(
      <ChildPreview
        role="topic"
        items={[row(2, "skill", "Sort arrays"), row(3, "subSkill", "Apply merge sort")]}
        fullWording={false}
      />,
    );

    const tiers = screen.getAllByRole("img").map((dot) => dot.getAttribute("aria-label"));
    expect(tiers).toEqual(["Skill", "Sub-skill"]);
    expect(screen.getByText("Sort arrays")).toBeTruthy();
  });

  it("lists a topic's first three children and counts the rest", () => {
    const items = [1, 2, 3, 4, 5].map((n) => row(n + 1, "skill", `Skill ${n}`));
    render(<ChildPreview role="topic" items={items} fullWording={false} />);

    expect(screen.queryByText("Skill 4")).toBeNull();
    expect(screen.getByText("+2 more")).toBeTruthy();
  });

  it("counts a skill's children by tier", () => {
    render(
      <ChildPreview
        role="skill"
        items={[row(2, "subSkill", "a"), row(3, "subSkill", "b"), row(4, "knowledge", "c")]}
        fullWording={false}
      />,
    );

    expect(screen.getByText("2 sub-skills")).toBeTruthy();
    expect(screen.getByText("1 knowledge")).toBeTruthy();
  });
});
