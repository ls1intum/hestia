import { useEffect, useMemo, useState } from "react";
import { useAutoAnimate } from "@formkit/auto-animate/react";
import type { LearningGoal } from "../api/client.ts";
import {
  COMPETENCY_ROLE_META,
  buildCompetencyForest,
  countPendingDescendants,
  type CompetencyNode,
  type CompetencyRole,
} from "../lib/goals.ts";

// Box geometry, kept in sync with the Tailwind classes below so the SVG connectors can be drawn
// from the layout alone (no DOM measuring): w-56 = 14rem and gap-3 = 0.75rem at a 16px root.
const BOX_W = 224;
const GAP = 12;
const PITCH = BOX_W + GAP; // centre-to-centre distance between adjacent boxes
const HALF = BOX_W / 2;
const CONNECTOR_H = 40;

/**
 * Competency map: a progressive drill-in laid out like a tree. The overview shows every terminal
 * competency at once in a wrapped grid (up to four per row). Click one and the grid gathers into
 * a single horizontal row — the selected competency glides to the left, its siblings line up to
 * its right — and a new row of its sub-skills unfolds beneath, joined to it by tree connectors.
 * Click a sub-skill and the same happens one tier down, revealing its knowledge. The expanded
 * tiers scroll horizontally so the boxes and connectors stay aligned; all motion (grid↔row +
 * reorder + unfolding) is handled by auto-animate. Plain, readable DOM — no canvas, zoom or pan.
 * Clicking a leaf, or the ⓘ on a branch box, opens the goal detail modal.
 */
export default function CompetencyGraph({
  goals,
  highlight,
  onOpenDetail,
  onToggleApproved,
  onEdit,
  onDelete,
}: {
  goals: LearningGoal[];
  highlight: "approved" | "unapproved" | null;
  onOpenDetail: (goal: LearningGoal) => void;
  onToggleApproved: (goal: LearningGoal) => void;
  onEdit: (goal: LearningGoal) => void;
  onDelete: (goal: LearningGoal) => void;
}) {
  const forest = useMemo(() => buildCompetencyForest(goals), [goals]);
  const actions = { onToggleApproved, onEdit, onDelete };

  // Drill path: [selected competency id, selected sub-skill id]. Empty = overview only.
  const [path, setPath] = useState<number[]>([]);

  const competency = useMemo(
    () => forest.find((n) => n.goal.id === path[0]) ?? null,
    [forest, path],
  );
  const subSkill = useMemo(
    () => competency?.children.find((n) => n.goal.id === path[1]) ?? null,
    [competency, path],
  );

  // A reload/re-extraction can drop a selected node; prune the path back to what still exists.
  useEffect(() => {
    if (path.length === 0) return;
    if (!competency) setPath([]);
    else if (path.length > 1 && !subSkill) setPath([path[0]]);
  }, [competency, subSkill, path]);

  // Toggle selection at a tier: re-selecting the active node collapses it (and everything below).
  const pickCompetency = (id: number) =>
    setPath((p) => (p[0] === id ? [] : [id]));
  const pickSubSkill = (id: number) =>
    setPath((p) => (p[1] === id ? [p[0]] : [p[0], id]));

  if (forest.length === 0) {
    return (
      <p className="rounded-xl border border-dashed border-hestia-border p-8 text-center text-sm text-hestia-text-muted">
        No competency tree for this course yet. Re-run the extraction to
        synthesise terminal competencies, sub-skills and knowledge.
      </p>
    );
  }

  // Each tier lives in its own horizontal scroll container, so a tier scrolls independently (the
  // tiers above stay put) and — crucially — drilling in does not resize the parent tier's
  // container, which would otherwise reset auto-animate's positions and kill its reorder animation.
  // Each tier's incoming connector rides inside its container, so it scrolls with its children.
  return (
    <div className="flex flex-col">
      {/* First-glance affordance: only in the overview — once drilled in it has done its job. */}
      {path.length === 0 && (
        <p className="px-1 text-sm text-hestia-text-muted">
          Click a skill to drill into its sub-skills and knowledge.
        </p>
      )}
      <Tier
        items={forest}
        activeId={path[0] ?? null}
        onPick={pickCompetency}
        onOpenDetail={onOpenDetail}
        actions={actions}
        highlight={highlight}
        isRoot
        layout={path.length > 0 ? "row" : "grid"}
      />
      {competency && (
        <Tier
          key={`level-sub-${competency.goal.id}`}
          items={competency.children}
          activeId={path[1] ?? null}
          onPick={pickSubSkill}
          onOpenDetail={onOpenDetail}
          actions={actions}
          highlight={highlight}
          connectorColor={COMPETENCY_ROLE_META.competency.color}
        />
      )}
      {subSkill && (
        <Tier
          key={`level-know-${subSkill.goal.id}`}
          items={subSkill.children}
          activeId={null}
          onPick={null}
          onOpenDetail={onOpenDetail}
          actions={actions}
          highlight={highlight}
          connectorColor={COMPETENCY_ROLE_META["sub-skill"].color}
        />
      )}
    </div>
  );
}

/**
 * One scroll-independent tier: its own horizontal scroll container holding the incoming connector
 * (unless it is the root) and the row of boxes. It tracks its own scroll offset so the connector
 * trunk and the sticky left-most box stay glued to the visible left edge while the rest scrolls.
 *
 * The root tier alternates between two layouts: a wrapped `grid` while nothing is selected (every
 * competency visible at once) and the horizontal `row` once one is. The elements stay the same
 * across the switch — only classes change — so auto-animate FLIPs the boxes from their grid spots
 * into the row (the selection reorders the children, which triggers the animation).
 */
function Tier({
  items,
  activeId,
  onPick,
  onOpenDetail,
  actions,
  highlight,
  connectorColor,
  isRoot = false,
  layout = "row",
}: {
  items: CompetencyNode[];
  activeId: number | null;
  onPick: ((id: number) => void) | null;
  onOpenDetail: (goal: LearningGoal) => void;
  actions: GoalActions;
  highlight: "approved" | "unapproved" | null;
  connectorColor?: string;
  isRoot?: boolean;
  layout?: "grid" | "row";
}) {
  const [scrollLeft, setScrollLeft] = useState(0);
  const grid = layout === "grid";
  return (
    <div
      className={`px-1 pb-2 ${grid ? "" : "overflow-x-auto"} ${isRoot ? "pt-2" : ""}`}
      onScroll={(e) => setScrollLeft(e.currentTarget.scrollLeft)}
    >
      <div
        className={`flex flex-col ${grid ? "" : "w-max"} ${isRoot ? "" : "comp-unfold"}`}
      >
        {connectorColor && (
          <Connector
            count={items.length}
            color={connectorColor}
            scrollLeft={scrollLeft}
          />
        )}
        <Row
          items={items}
          activeId={activeId}
          onPick={onPick}
          onOpenDetail={onOpenDetail}
          actions={actions}
          highlight={highlight}
          scrolled={scrollLeft > 0}
          grid={grid}
        />
      </div>
    </div>
  );
}

/**
 * Tree connector between the parent box (one tier up) and its children's row. The links share a
 * trunk down from the parent and a horizontal rail, then drop a parallel stub to each child — the
 * standard tree routing, so nothing overlaps; joins are rounded so there are no sharp corners.
 *
 * The trunk is kept at the tier's visible left edge by offsetting it by `scrollLeft`. A sub-tier's
 * left-most box is sticky (pinned to that same edge), so the trunk stays glued to it and up to the
 * parent however far the tier is scrolled — the line to the parent above is always visible.
 * Children that scroll behind the sticky box are dropped from the rail, so the connector always
 * reads as "parent → left-most child → the rest".
 */
function Connector({
  count,
  color,
  scrollLeft,
}: {
  count: number;
  color: string;
  scrollLeft: number;
}) {
  if (count === 0) return null;
  const width = (count - 1) * PITCH + BOX_W;
  const mid = CONNECTOR_H / 2;
  const r = 8; // corner radius for the rounded joins
  const trunk = scrollLeft + HALF; // follow the visible left edge / the sticky first box

  // Children that scroll behind the sticky box are hidden, so we skip them.
  const visible: number[] = [];
  for (let i = 1; i < count; i++) {
    const cx = HALF + i * PITCH;
    if (cx > trunk + r) visible.push(cx);
  }

  return (
    <svg
      width={width}
      height={CONNECTOR_H}
      className="block shrink-0"
      aria-hidden="true"
    >
      <g stroke={color} strokeWidth={1.5} fill="none" strokeLinecap="round">
        {/* Trunk down to the sticky left-most child: a straight drop at the visible left edge. */}
        <path d={`M ${trunk} 0 V ${CONNECTOR_H}`} />
        {/* Each remaining child: trunk down, rounded turn onto the rail, across, rounded turn, drop. */}
        {visible.map((cx, i) => (
          <path
            key={i}
            d={`M ${trunk} 0 V ${mid - r} Q ${trunk} ${mid} ${trunk + r} ${mid} H ${cx - r} Q ${cx} ${mid} ${cx} ${mid + r} V ${CONNECTOR_H}`}
          />
        ))}
      </g>
    </svg>
  );
}

/**
 * One tier of the drill-in. Items animate into place; the active node is pinned to the left and
 * the rest follow in their original order. `onPick` is null for the leaf tier (knowledge/gaps),
 * whose boxes have nothing to unfold and so just open the detail modal.
 */
/** The review actions shared by every box, mirroring the list view's approve / edit / delete. */
type GoalActions = {
  onToggleApproved: (goal: LearningGoal) => void;
  onEdit: (goal: LearningGoal) => void;
  onDelete: (goal: LearningGoal) => void;
};

function Row({
  items,
  activeId,
  onPick,
  onOpenDetail,
  actions,
  highlight,
  scrolled,
  grid = false,
}: {
  items: CompetencyNode[];
  activeId: number | null;
  onPick: ((id: number) => void) | null;
  onOpenDetail: (goal: LearningGoal) => void;
  actions: GoalActions;
  highlight: "approved" | "unapproved" | null;
  scrolled: boolean;
  grid?: boolean;
}) {
  const [rowRef] = useAutoAnimate<HTMLDivElement>();

  // Active node first, the rest in their natural order.
  const ordered = useMemo(() => {
    if (activeId == null) return items;
    const active = items.filter((n) => n.goal.id === activeId);
    const rest = items.filter((n) => n.goal.id !== activeId);
    return [...active, ...rest];
  }, [items, activeId]);

  return (
    <div
      ref={rowRef}
      className={
        grid
          ? "grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
          : "flex gap-3"
      }
    >
      {ordered.map((node, i) => {
        const expandable = onPick != null && node.children.length > 0;
        // The left-most box is pinned only when it is the selected node (selection moves it to the
        // front), so its unfolded children below stay anchored to it while the row scrolls. With
        // nothing selected the row scrolls freely and so does the connector above it.
        const sticky = i === 0 && activeId != null;
        const approved = node.goal.status === "APPROVED";
        const dimmed =
          highlight != null && (highlight === "approved") !== approved;
        // Once a sibling is selected, the rest recede a little — still readable and clickable
        // (one-click switching), hover brings a box back to full strength.
        const receded = activeId != null && node.goal.id !== activeId;
        return (
          <Box
            key={node.goal.id}
            node={node}
            active={node.goal.id === activeId}
            expandable={expandable}
            onClick={() =>
              expandable ? onPick!(node.goal.id!) : onOpenDetail(node.goal)
            }
            onOpenDetail={() => onOpenDetail(node.goal)}
            actions={actions}
            stuck={sticky && scrolled}
            dimmed={dimmed}
            receded={receded}
            fluid={grid}
          />
        );
      })}
    </div>
  );
}

/** A readable competency/sub-skill/knowledge rectangle. Branch boxes carry a child count and an
 * unfold chevron; every box carries the review actions (details / approve / edit / delete), which
 * fade in on hover and each explain themselves via a tooltip. Clicking the body unfolds a branch
 * or opens the detail of a leaf. It is a div (not a button) so the action buttons can nest. */
function Box({
  node,
  active,
  expandable,
  onClick,
  onOpenDetail,
  actions,
  stuck,
  dimmed,
  receded = false,
  fluid = false,
}: {
  node: CompetencyNode;
  active: boolean;
  expandable: boolean;
  onClick: () => void;
  onOpenDetail: () => void;
  actions: GoalActions;
  stuck: boolean;
  dimmed: boolean;
  /** Unselected sibling of the tier's active box — softened but still clickable. */
  receded?: boolean;
  /** In the grid overview the box fills its cell; in a row it keeps the fixed connector width. */
  fluid?: boolean;
}) {
  const meta = COMPETENCY_ROLE_META[node.role];
  const isGap = node.role === "gap";
  const childCount = node.children.length;
  const approved = node.goal.status === "APPROVED";
  const pendingBelow = countPendingDescendants(node);
  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onClick}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onClick();
        }
      }}
      title={expandable ? "Unfold" : "View goal details"}
      className={`group relative flex cursor-pointer flex-col gap-1.5 rounded-lg border-t-4 border p-3 text-left shadow-sm transition ${
        fluid ? "" : "w-56 shrink-0"
      } ${
        isGap
          ? "border-hestia-danger/40 bg-[color-mix(in_srgb,var(--hestia-danger)_8%,var(--hestia-surface))] hover:border-hestia-danger"
          : "border-hestia-border bg-hestia-surface hover:border-hestia-primary hover:bg-hestia-bg"
      } ${
        // Only pin once the row is actually scrolled — at scroll 0 sticky has no visible effect but
        // would race auto-animate's FLIP measurement and break the slide-to-front animation.
        stuck
          ? "sticky left-0 z-10 shadow-[6px_0_8px_-6px_rgba(0,0,0,0.35)]"
          : ""
      } ${dimmed ? "opacity-30" : receded ? "opacity-50 hover:opacity-100" : ""}`}
      style={{
        borderTopColor: meta.color,
        // The selected box keeps only this quiet tint — the receding siblings do the highlighting.
        ...(active
          ? {
              backgroundColor: `color-mix(in srgb, ${meta.color} 12%, var(--hestia-surface))`,
              borderColor: meta.color,
            }
          : {}),
      }}
    >
      {/* Header: role (and the pending count) on the left, the approve checkmark pinned right. */}
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <RoleBadge role={node.role} />
          {/* How many goals beneath this branch still await review — guides the drill-in. */}
          {pendingBelow > 0 && (
            <span
              className="flex items-center gap-1 text-[0.7rem] font-medium text-hestia-warning"
              title={`${pendingBelow} goal${pendingBelow === 1 ? "" : "s"} below still need review`}
            >
              <span
                className="h-1.5 w-1.5 shrink-0 rounded-full bg-hestia-warning"
                aria-hidden="true"
              />
              {pendingBelow} pending
            </span>
          )}
        </div>
        <BoxAction
          label={approved ? "Unapprove goal" : "Approve goal"}
          tip={
            approved
              ? "Approved — click to move this goal back to pending."
              : "Approve this goal — marks it as reviewed and accepted."
          }
          alwaysVisible={approved}
          tipBelow
          onClick={() => actions.onToggleApproved(node.goal)}
          className={
            approved
              ? "text-hestia-accent hover:bg-[color-mix(in_srgb,var(--hestia-accent)_15%,transparent)]"
              : "text-hestia-text-muted hover:bg-hestia-primary-muted hover:text-hestia-text"
          }
        >
          <svg
            viewBox="0 0 20 20"
            fill={approved ? "currentColor" : "none"}
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="h-5 w-5"
          >
            <circle cx="10" cy="10" r="7.5" />
            <path
              d="M6.5 10.5l2.5 2.5 4.5-5"
              stroke={approved ? "var(--hestia-surface)" : "currentColor"}
              fill="none"
            />
          </svg>
        </BoxAction>
      </div>
      <p
        className={`text-[0.8rem] font-medium leading-snug ${
          isGap ? "text-hestia-danger" : "text-hestia-text"
        }`}
      >
        {node.goal.text}
      </p>
      <div className="mt-auto flex items-center gap-1 pt-1">
        {expandable && (
          <span className="flex items-center gap-1 text-xs text-hestia-text-muted">
            <span className="tabular-nums">
              {childCount} {node.role === "competency" ? "sub-skill" : "item"}
              {childCount === 1 ? "" : "s"}
            </span>
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className={`h-3.5 w-3.5 transition-transform ${active ? "rotate-90" : ""}`}
            >
              <path d="M7 5l6 5-6 5" />
            </svg>
          </span>
        )}
        <div className="ml-auto flex items-center gap-0.5">
          <BoxAction
            label="View details"
            tip="Open the full goal — sources and related goals."
            onClick={onOpenDetail}
            className="text-hestia-text-muted hover:bg-hestia-primary-muted hover:text-hestia-text"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4"
            >
              <circle cx="10" cy="10" r="7.5" />
              <path d="M10 9v4" />
              <path d="M10 6.5h.01" />
            </svg>
          </BoxAction>
          <BoxAction
            label="Edit goal"
            tip="Edit this goal's wording and its Bloom / SOLO level."
            onClick={() => actions.onEdit(node.goal)}
            className="text-hestia-text-muted hover:bg-hestia-primary-muted hover:text-hestia-text"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4"
            >
              <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
            </svg>
          </BoxAction>
          <BoxAction
            label="Delete goal"
            tip="Delete this goal permanently."
            onClick={() => actions.onDelete(node.goal)}
            className="text-hestia-text-muted hover:bg-hestia-danger hover:text-white"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4"
            >
              <path d="M4 6h12M8 6V4h4v2M6 6l1 10h6l1-10" />
            </svg>
          </BoxAction>
        </div>
      </div>
    </div>
  );
}

/**
 * Icon action inside a box, mirroring the list view's CardAction: stops propagation so it doesn't
 * also unfold/open the box, fades in on box hover (unless `alwaysVisible`), and reveals a tooltip
 * describing what it does on its own hover.
 */
function BoxAction({
  label,
  tip,
  onClick,
  className,
  alwaysVisible,
  tipBelow,
  children,
}: {
  label: string;
  tip: string;
  onClick: () => void;
  className: string;
  alwaysVisible?: boolean;
  tipBelow?: boolean;
  children: React.ReactNode;
}) {
  return (
    <span className="group/tip relative inline-flex">
      <button
        type="button"
        title={label}
        aria-label={label}
        onClick={(e) => {
          e.stopPropagation();
          onClick();
        }}
        className={`flex h-7 w-7 items-center justify-center rounded-md transition focus-visible:opacity-100 ${
          alwaysVisible ? "" : "opacity-0 group-hover:opacity-100"
        } ${className}`}
      >
        {children}
      </button>
      <span
        role="tooltip"
        className={`pointer-events-none absolute right-0 z-30 hidden w-44 rounded-lg border border-hestia-border border-l-[3px] border-l-hestia-primary bg-hestia-surface p-2 text-left text-[0.7rem] font-normal normal-case leading-snug text-hestia-text shadow-lg group-hover/tip:block ${
          tipBelow ? "top-full mt-1.5" : "bottom-full mb-1.5"
        }`}
      >
        {tip}
      </span>
    </span>
  );
}

function RoleBadge({ role }: { role: CompetencyRole }) {
  const meta = COMPETENCY_ROLE_META[role];
  const isGap = role === "gap";
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[0.6rem] font-semibold uppercase tracking-wide"
      style={{
        color: meta.color,
        backgroundColor: `color-mix(in srgb, ${meta.color} 15%, transparent)`,
      }}
    >
      {isGap && <GapIcon />}
      {meta.label}
    </span>
  );
}

function GapIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-3 w-3"
    >
      <path d="M10 3.5L2.5 16.5h15z" />
      <path d="M10 8v3.5" />
      <path d="M10 14h.01" />
    </svg>
  );
}
