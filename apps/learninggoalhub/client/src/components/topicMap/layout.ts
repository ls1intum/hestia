import type { CompetencyNode } from "../../lib/goals.ts";

// Box geometry, kept in sync with the boxes' Tailwind classes so the SVG connectors can be drawn
// from the layout alone (no DOM measuring): w-40 = 10rem, w-56 = 14rem, w-60 = 15rem and
// gap-3 = 0.75rem at a 16px root.
export const BOX_W = 224;
export const COMPACT_W = 160; // dimmed sibling boxes in a row with a focused box
export const LEAF_W = 240; // boxes in the second and third rows
export const GHOST_W = 128; // the quiet "+ New …" pill beside a row
export const HINT_W = 168; // the note standing in for the pill beside the "Additional sub-skills" row
export const GAP = 12;
export const CONNECTOR_H = 40;
export const SCROLL_STEP = 332; // one scroll-arrow press
export const EDGE_W = 56; // the scroll fade at either edge (w-14)

export function rowWidth(widths: number[]) {
  return widths.reduce((total, width) => total + width, 0) +
    Math.max(0, widths.length - 1) * GAP;
}

/** Centre positions measured from the left edge of a flex row with the given child widths. */
export function childCentres(widths: number[]) {
  let left = 0;
  return widths.map((width) => {
    const centre = left + width / 2;
    left += width + GAP;
    return centre;
  });
}

/**
 * Space a row's creation control takes to the left of its boxes. An open creation field needs a
 * box's width.
 */
export function ghostReserve(present: boolean, active: boolean) {
  if (!present) return 0;
  return (active ? BOX_W : GHOST_W) + GAP;
}

/**
 * The "add a goal here" state the table owns. The map only renders the creation controls, so a
 * creation started in the map and one started in the table can never both be open.
 */
export type MapCreation = {
  activeKey: string | null;
  value: string;
  pending: boolean;
  /** The failure of the creation currently open, if any. */
  error: string | undefined;
  begin: (tier: 2 | 3 | 4, parentGoalId: number) => void;
  change: (value: string) => void;
  submit: () => void;
  cancel: () => void;
};

export const LOOSE_GROUP_LABEL = "Additional sub-skills";

/** The first row's box gathering the topic's goals that sit in no skill. */
export function isLooseGroup(node: CompetencyNode) {
  return node.goal.id! < 0;
}

/** Skills and sub-skills that hold goals unfold into a row of their own; knowledge and gaps don't. */
export function canDrill(node: CompetencyNode) {
  return (node.role === "skill" || node.role === "subSkill") && node.children.length > 0;
}

/** How many children a closed box names as mini boxes before summing up the rest. */
export const MINI_LIMIT = 3;
