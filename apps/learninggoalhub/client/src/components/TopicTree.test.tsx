// @vitest-environment jsdom
import "../test/dom.ts";
import { beforeAll, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { LearningGoal } from "../api/client.ts";

vi.mock("../api/client.ts", () => ({ API_PREFIX: "", api: {} }));
const { default: TopicTree } = await import("./TopicTree.tsx");

beforeAll(() => {
  // jsdom implements neither; the grid measures itself with both.
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  window.matchMedia = vi.fn().mockReturnValue({
    matches: false,
    addEventListener: () => {},
    removeEventListener: () => {},
  });
});

function goal(id: number, text: string, parent?: number): LearningGoal {
  return {
    id,
    text,
    shortLabel: text,
    // A topic is a TERMINAL goal; the server marks skills and sub-skills alike as SKILL.
    role: parent ? "SKILL" : undefined,
    origin: parent ? "EXTRACTED" : "TERMINAL",
    sources: [],
    relationships: parent
      ? [{ type: "CONTRIBUTES_TO", targetGoalId: parent, targetText: "", confidence: 1, origin: "HIERARCHY" }]
      : [],
  } as LearningGoal;
}

const goals = [
  goal(1, "Sorting"),
  goal(2, "Choose a sorting algorithm", 1),
  goal(3, "Apply merge sort", 2),
];

function renderTree() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <TopicTree courseId={1} goals={goals} onUpdate={() => {}} onDelete={() => {}} />
    </QueryClientProvider>,
  );
  return screen.getByRole("table", { name: "Topic tree" });
}

describe("TopicTree", () => {
  it("unfolds a topic into its skills and folds it back", async () => {
    const user = userEvent.setup();
    const table = renderTree();
    const expand = within(table).getAllByRole("button", { name: "Expand" })[0];

    // Folded, the topic previews its skill by name only; the skill's own row, which counts its
    // sub-skills, appears once the topic unfolds.
    expect(within(table).queryByText("1 sub-skill")).toBeNull();

    await user.click(expand);
    expect(expand.getAttribute("aria-expanded")).toBe("true");
    expect(within(table).getByText("1 sub-skill")).toBeTruthy();

    await user.click(within(table).getAllByRole("button", { name: "Collapse" })[0]);
    // The rows fade out before they leave.
    await vi.waitFor(() => expect(expand.getAttribute("aria-expanded")).toBe("false"));
    expect(within(table).queryByText("1 sub-skill")).toBeNull();
  });
});
