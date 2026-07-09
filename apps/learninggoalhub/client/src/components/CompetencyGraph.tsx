import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { LearningGoal } from "../api/client.ts";
import {
  COMPETENCY_ROLE_META,
  buildCompetencyForest,
  countPendingDescendants,
  type CompetencyNode,
  type CompetencyRole,
} from "../lib/goals.ts";

// Box geometry, kept in sync with the Tailwind classes below so the SVG connectors can be drawn
// from the layout alone (no DOM measuring): w-56 = 14rem, w-80 = 20rem and gap-3 = 0.75rem at a
// 16px root.
const BOX_W = 224;
const WIDE_W = 320; // drill-path boxes and the knowledge row under a focused sub-skill
const GAP = 12;
const CONNECTOR_H = 40;

/**
 * Competency map: a focus-and-context tree. The overview shows every terminal competency as a
 * collapsed tree — a card with sub-skill/knowledge counts and a small root-to-leaves schematic
 * hinting that three tiers unfold beneath it. Click one and it becomes the focused tree: the
 * card moves to the centre with its sub-skills fanned out below it. Its siblings collapse into
 * a quiet pill above ("Other skills · 7"); one tier down, the focused sub-skill's siblings
 * collapse into a pill sitting ON the skill → sub-skill edge, joined by short trunk segments.
 * Clicking a pill opens an overlay with the overview-style card grid (cards staggering in) to
 * switch the focus at that tier; any navigation closes it. The centre column is always the
 * drill path. The whole tree shares one horizontal scroll container so boxes and connectors
 * stay aligned; each focus change re-centres it. Clicking a leaf, or the ⓘ on a branch box,
 * opens the goal detail modal.
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

  // Which sibling picker overlay is open; any focus change closes it again.
  const [picker, setPicker] = useState<"skills" | "subs" | null>(null);
  useEffect(() => setPicker(null), [path]);

  const competency = useMemo(
    () => forest.find((n) => n.goal.id === path[0]) ?? null,
    [forest, path],
  );
  const subSkill = useMemo(
    () => competency?.children.find((n) => n.goal.id === path[1]) ?? null,
    [competency, path],
  );

  // A reload/re-extraction can drop a selected node; prune the path back to what still exists.
  // Plain setPath — nothing was clicked, so nothing should animate.
  useEffect(() => {
    if (path.length === 0) return;
    if (!competency) setPath([]);
    else if (path.length > 1 && !subSkill) setPath([path[0]]);
  }, [competency, subSkill, path]);

  // FLIP: `navigate` snapshots every visible box (by goal id) before the path changes; after the
  // new layout is in, each box that survived the transition — the clicked one gliding into its
  // focus slot, the parent rising onto the path, a card flying out of the picker overlay — is
  // animated from its old viewport rect to its new one. Boxes without a previous rect simply
  // appear (their `comp-pop` entrance), so a programmatic path change animates nothing.
  const containerRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const flipRects = useRef<Map<string, DOMRect> | null>(null);

  const navigate = (next: number[]) => {
    const map = new Map<string, DOMRect>();
    containerRef.current
      ?.querySelectorAll<HTMLElement>("[data-goal-id]")
      .forEach((el) => map.set(el.dataset.goalId!, el.getBoundingClientRect()));
    flipRects.current = map;
    setPath(next);
  };

  useLayoutEffect(() => {
    // Re-centre the tree first, so the boxes are measured in their final resting places.
    const scroller = scrollRef.current;
    if (scroller)
      scroller.scrollLeft = (scroller.scrollWidth - scroller.clientWidth) / 2;

    const prev = flipRects.current;
    flipRects.current = null;
    if (
      !prev ||
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
    )
      return;
    containerRef.current
      ?.querySelectorAll<HTMLElement>("[data-goal-id]")
      .forEach((el) => {
        const from = prev.get(el.dataset.goalId!);
        if (!from) return;
        const to = el.getBoundingClientRect();
        const dx = from.left - to.left;
        const dy = from.top - to.top;
        const sx = from.width / to.width;
        const sy = from.height / to.height;
        if (Math.abs(dx) < 1 && Math.abs(dy) < 1 && Math.abs(sx - 1) < 0.01)
          return;
        // A surviving box slides — it must not ALSO play an entrance animation, neither its
        // own nor an enclosing wrapper's (the state-2 cells carry `comp-pop`).
        for (
          let n: HTMLElement | null = el;
          n && n !== containerRef.current;
          n = n.parentElement
        ) {
          n.getAnimations().forEach((a) => {
            if (a instanceof CSSAnimation && a.animationName.startsWith("comp-"))
              a.cancel();
          });
        }
        el.style.transformOrigin = "top left";
        el.animate(
          [
            { transform: `translate(${dx}px, ${dy}px) scale(${sx}, ${sy})` },
            { transform: "none" },
          ],
          { duration: 300, easing: "cubic-bezier(0.2, 0, 0.2, 1)" },
        );
      });
  }, [path]);

  // Toggle selection at a tier: re-selecting the active node collapses it (and everything below).
  const pickCompetency = (id: number) =>
    navigate(path[0] === id ? [] : [id]);
  const pickSubSkill = (id: number) =>
    navigate(path[1] === id ? [path[0]] : [path[0], id]);

  if (forest.length === 0) {
    return (
      <p className="rounded-xl border border-dashed border-hestia-border p-8 text-center text-sm text-hestia-text-muted">
        No competency tree for this course yet. Re-run the extraction to
        synthesise terminal competencies, sub-skills and knowledge.
      </p>
    );
  }

  // Overview: every competency as a collapsed tree in a wrapped grid.
  if (!competency) {
    return (
      <div ref={containerRef} className="flex flex-col gap-3 pt-1">
        <p className="px-1 text-sm text-hestia-text-muted">
          Click a skill to focus its tree — sub-skills unfold below it, the
          other skills move to a shelf above.
        </p>
        <div className="grid gap-3 px-1 pb-2 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {forest.map((node) => {
            const expandable = node.children.length > 0;
            const deep = countGrandchildren(node);
            return (
              // Two-row cell: the card stretches to the row height, and the collapsed-tree
              // schematic branches off UNDERNEATH it — outside the card, like a folded-up
              // version of the connectors that unfold on click.
              <div key={node.goal.id} className="grid grid-rows-[1fr_auto]">
                <Box
                  node={node}
                  active={false}
                  expandable={expandable}
                  onClick={() =>
                    expandable
                      ? pickCompetency(node.goal.id!)
                      : onOpenDetail(node.goal)
                  }
                  onOpenDetail={() => onOpenDetail(node.goal)}
                  actions={actions}
                  dimmed={isDimmed(node, highlight)}
                  fluid
                  deepKnowledge={deep}
                />
                {expandable && (
                  <div className="justify-self-center">
                    <Stub
                      childCount={node.children.length}
                      hasKnowledge={deep > 0}
                    />
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    );
  }

  const skillColor = COMPETENCY_ROLE_META.competency.color;
  const subColor = COMPETENCY_ROLE_META["sub-skill"].color;
  const otherSkills = forest.filter((n) => n.goal.id !== competency.goal.id);
  const otherSubs = subSkill
    ? competency.children.filter((n) => n.goal.id !== subSkill.goal.id)
    : [];

  // Focused tree. The canvas is keyed by the drill path so a focus change replays the unfold
  // animation; `w-max` + `mx-auto` centre it when it fits and let it scroll as one unit when not.
  return (
    <div ref={containerRef} className="flex flex-col gap-2 px-1 pt-1">
      {otherSkills.length > 0 && (
        <div className="flex justify-center">
          <ShelfPill
            label="Other skills"
            count={otherSkills.length}
            color={skillColor}
            onClick={() => setPicker("skills")}
          />
        </div>
      )}
      <div ref={scrollRef} className="overflow-x-auto pb-2">
        {/* Keyed by the drill path so every navigation remounts the tiers — the connectors
            redraw and the children replay their entrance, while surviving boxes FLIP. */}
        <div
          key={`tree-${competency.goal.id}-${subSkill?.goal.id ?? "none"}`}
          className="mx-auto flex w-max min-w-full flex-col items-center"
        >
          {subSkill == null ? (
            <>
              <Box
                node={competency}
                active
                expandable
                onClick={() => pickCompetency(competency.goal.id!)}
                onOpenDetail={() => onOpenDetail(competency.goal)}
                actions={actions}
                dimmed={isDimmed(competency, highlight)}
                wide
              />
              <Connector
                count={competency.children.length}
                color={skillColor}
              />
              <div className="flex justify-center gap-3">
                {competency.children.map((child, i) => {
                  const expandable = child.children.length > 0;
                  return (
                    // Column cell: the box plus, when knowledge waits beneath, the mini leaf
                    // indicator branching off below it. Stub width < box width, so the cell
                    // keeps the box's footprint and the connector above stays aligned. The
                    // cell pops in after the focused box has slid into place (FLIP cancels
                    // this entrance on a box that survived the transition).
                    <div
                      key={child.goal.id}
                      className="comp-pop flex flex-col items-center"
                      style={{ animationDelay: `${180 + i * 30}ms` }}
                    >
                      <Box
                        node={child}
                        active={false}
                        expandable={expandable}
                        onClick={() =>
                          expandable
                            ? pickSubSkill(child.goal.id!)
                            : onOpenDetail(child.goal)
                        }
                        onOpenDetail={() => onOpenDetail(child.goal)}
                        actions={actions}
                        dimmed={isDimmed(child, highlight)}
                      />
                      {expandable && (
                        <LeafStub count={child.children.length} />
                      )}
                    </div>
                  );
                })}
              </div>
            </>
          ) : (
            <>
              {/* The drill path's parent: quieter than the focus node, click to go back up. */}
              <Box
                node={competency}
                active={false}
                open
                expandable
                onClick={() => navigate([competency.goal.id!])}
                onOpenDetail={() => onOpenDetail(competency.goal)}
                actions={actions}
                dimmed={isDimmed(competency, highlight)}
                subdued
                wide
              />
              {otherSubs.length > 0 ? (
                <>
                  {/* The sibling picker pill sits at its own tree depth, ON the edge between
                      parent and focus; the trunk segments keep that edge readable through it. */}
                  <VLine color={skillColor} />
                  <ShelfPill
                    label="Other sub-skills"
                    count={otherSubs.length}
                    color={subColor}
                    onClick={() => setPicker("subs")}
                  />
                  <VLine color={skillColor} />
                </>
              ) : (
                <Connector count={1} color={skillColor} />
              )}
              <Box
                node={subSkill}
                active
                expandable
                onClick={() => pickSubSkill(subSkill.goal.id!)}
                onOpenDetail={() => onOpenDetail(subSkill.goal)}
                actions={actions}
                dimmed={isDimmed(subSkill, highlight)}
                wide
              />
              <Connector
                count={subSkill.children.length}
                color={subColor}
                childW={WIDE_W}
              />
              <div className="flex justify-center gap-3">
                {subSkill.children.map((leaf, i) => (
                  // The knowledge pops in after the focused sub-skill has slid into place.
                  <div
                    key={leaf.goal.id}
                    className="comp-pop"
                    style={{ animationDelay: `${180 + i * 30}ms` }}
                  >
                    <Box
                      node={leaf}
                      active={false}
                      expandable={false}
                      onClick={() => onOpenDetail(leaf.goal)}
                      onOpenDetail={() => onOpenDetail(leaf.goal)}
                      actions={actions}
                      dimmed={isDimmed(leaf, highlight)}
                      wide
                    />
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </div>
      {picker === "skills" && (
        <ShelfOverlay
          label="Other skills"
          items={otherSkills}
          kind="skills"
          onPick={pickCompetency}
          onOpenDetail={onOpenDetail}
          actions={actions}
          highlight={highlight}
          onClose={() => setPicker(null)}
        />
      )}
      {picker === "subs" && subSkill != null && (
        <ShelfOverlay
          label="Other sub-skills"
          items={otherSubs}
          kind="subs"
          onPick={pickSubSkill}
          onOpenDetail={onOpenDetail}
          actions={actions}
          highlight={highlight}
          onClose={() => setPicker(null)}
        />
      )}
    </div>
  );
}

function isDimmed(
  node: CompetencyNode,
  highlight: "approved" | "unapproved" | null,
) {
  if (highlight == null) return false;
  return (highlight === "approved") !== (node.goal.status === "APPROVED");
}

/** Total knowledge two tiers down — shown on overview cards so the depth reads at a glance. */
function countGrandchildren(node: CompetencyNode) {
  return node.children.reduce((n, child) => n + child.children.length, 0);
}

/**
 * Collapsed sibling picker: one quiet pill naming the tier and how many siblings wait behind
 * it. Clicking opens the ShelfOverlay to switch the focus at that tier.
 */
function ShelfPill({
  label,
  count,
  color,
  onClick,
}: {
  label: string;
  count: number;
  color: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full border border-dashed px-2.5 py-1 text-[0.6rem] font-semibold uppercase tracking-wider shadow-sm transition hover:shadow"
      style={{
        borderColor: `color-mix(in srgb, ${color} 45%, transparent)`,
        backgroundColor: `color-mix(in srgb, ${color} 4%, transparent)`,
        color: `color-mix(in srgb, ${color} 80%, var(--hestia-text))`,
      }}
    >
      {label} · {count}
      <svg
        viewBox="0 0 20 20"
        fill="none"
        stroke="currentColor"
        strokeWidth="2.5"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className="h-2.5 w-2.5"
      >
        <path d="M7 5l6 5-6 5" />
      </svg>
    </button>
  );
}

/**
 * Sibling picker overlay: the same cards as the page-one overview (skills keep their counts and
 * collapsed-tree schematic), staggering onto the page over the tree. Picking one focuses it —
 * the path change closes the overlay; backdrop click, Escape and the ✕ close it without picking.
 * Sits at z-40 so the goal detail modal (z-50, reachable via a card's ⓘ) stacks above it.
 */
function ShelfOverlay({
  label,
  items,
  kind,
  onPick,
  onOpenDetail,
  actions,
  highlight,
  onClose,
}: {
  label: string;
  items: CompetencyNode[];
  kind: "skills" | "subs";
  onPick: (id: number) => void;
  onOpenDetail: (goal: LearningGoal) => void;
  actions: GoalActions;
  highlight: "approved" | "unapproved" | null;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-40 flex items-start justify-center overflow-y-auto bg-black/50 p-4 sm:p-8"
      role="dialog"
      aria-modal="true"
      aria-label={label}
    >
      {/* No panel chrome — the cards float freely over the dimmed tree. */}
      <div
        onClick={(e) => e.stopPropagation()}
        className="flex w-full max-w-4xl flex-col gap-3"
      >
        <div className="flex items-center justify-between">
          <span className="text-[0.6rem] font-semibold uppercase tracking-wider text-white/90">
            {label}
          </span>
          <button
            onClick={onClose}
            aria-label="Close"
            className="flex h-8 w-8 items-center justify-center rounded-md text-white/80 transition hover:bg-white/10 hover:text-white"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              className="h-4 w-4"
            >
              <path d="M5 5l10 10M15 5L5 15" />
            </svg>
          </button>
        </div>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {items.map((node, i) => {
            const expandable = node.children.length > 0;
            const deep = kind === "skills" ? countGrandchildren(node) : null;
            return (
              // Two-row grid wrapper: carries the staggered entrance without disturbing the
              // box's own layout, stretches it to the row height, and hangs the collapsed-tree
              // indicator underneath — the same footprint the card has in the tree/overview.
              <div
                key={node.goal.id}
                className="comp-unfold grid grid-rows-[1fr_auto]"
                style={{
                  animationDelay: `${i * 35}ms`,
                  animationFillMode: "backwards",
                }}
              >
                <Box
                  node={node}
                  active={false}
                  expandable={expandable}
                  onClick={() =>
                    expandable ? onPick(node.goal.id!) : onOpenDetail(node.goal)
                  }
                  onOpenDetail={() => onOpenDetail(node.goal)}
                  actions={actions}
                  dimmed={isDimmed(node, highlight)}
                  fluid
                  deepKnowledge={deep}
                />
                {expandable && (
                  <div className="justify-self-center">
                    {kind === "skills" ? (
                      <Stub
                        childCount={node.children.length}
                        hasKnowledge={(deep ?? 0) > 0}
                      />
                    ) : (
                      <LeafStub count={node.children.length} />
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

/** Short vertical trunk segment continuing the parent → focus edge through a `between` shelf. */
function VLine({ color }: { color: string }) {
  return (
    <div
      aria-hidden="true"
      className="h-[18px] w-[1.5px] shrink-0 rounded-full"
      style={{ backgroundColor: color }}
    />
  );
}

/**
 * Tree connector between a centred parent box and its children's row. The trunk drops from the
 * parent's centre, turns onto a horizontal rail and drops a stub to each child — the standard
 * tree routing, mirrored for children left of the trunk; joins are rounded so there are no sharp
 * corners. Geometry comes from the shared box constants, so no DOM measuring is needed: parent
 * and children are centred in the same column, which puts the trunk at the row's midpoint.
 */
function Connector({
  count,
  color,
  childW = BOX_W,
}: {
  count: number;
  color: string;
  /** Width of the child boxes below — matches their `wide`/default Tailwind width. */
  childW?: number;
}) {
  if (count === 0) return null;
  const pitch = childW + GAP; // centre-to-centre distance between adjacent children
  const width = (count - 1) * pitch + childW;
  const trunk = width / 2;
  const mid = CONNECTOR_H / 2;
  const r = 8; // corner radius for the rounded joins

  // `pathLength={1}` normalises every path so the comp-draw dash animation can draw each link
  // from the trunk toward its child, whatever the actual path length.
  return (
    <svg
      width={width}
      height={CONNECTOR_H}
      className="comp-draw block shrink-0"
      aria-hidden="true"
    >
      <g stroke={color} strokeWidth={1.5} fill="none" strokeLinecap="round">
        {Array.from({ length: count }, (_, i) => {
          const cx = childW / 2 + i * pitch;
          const dx = cx - trunk;
          // Too close to the trunk for the rail-and-corners route: a gentle S-curve instead
          // (straight drop when the child sits exactly under the trunk).
          if (Math.abs(dx) < 2 * r) {
            return (
              <path
                key={i}
                pathLength={1}
                d={`M ${trunk} 0 C ${trunk} ${mid} ${cx} ${mid} ${cx} ${CONNECTOR_H}`}
              />
            );
          }
          const sg = dx > 0 ? 1 : -1;
          return (
            <path
              key={i}
              pathLength={1}
              d={`M ${trunk} 0 V ${mid - r} Q ${trunk} ${mid} ${trunk + sg * r} ${mid} H ${cx - sg * r} Q ${cx} ${mid} ${cx} ${mid + r} V ${CONNECTOR_H}`}
            />
          );
        })}
      </g>
    </svg>
  );
}

/** The review actions shared by every box, mirroring the list view's approve / edit / delete. */
type GoalActions = {
  onToggleApproved: (goal: LearningGoal) => void;
  onEdit: (goal: LearningGoal) => void;
  onDelete: (goal: LearningGoal) => void;
};

/** A readable competency/sub-skill/knowledge rectangle. Branch boxes carry a child count and an
 * unfold chevron (overview cards add the deep knowledge count and a collapsed-tree schematic);
 * every box carries the review actions (details / approve / edit / delete), which fade in on
 * hover and each explain themselves via a tooltip. Clicking the body unfolds a branch or opens
 * the detail of a leaf. It is a div (not a button) so the action buttons can nest. */
function Box({
  node,
  active,
  open = active,
  expandable,
  onClick,
  onOpenDetail,
  actions,
  dimmed,
  fluid = false,
  wide = false,
  subdued = false,
  deepKnowledge = null,
}: {
  node: CompetencyNode;
  active: boolean;
  /** Chevron state: the box is unfolded. Defaults to `active`; the path parent sets it alone. */
  open?: boolean;
  expandable: boolean;
  onClick: () => void;
  onOpenDetail: () => void;
  actions: GoalActions;
  dimmed: boolean;
  /** In the grid overview the box fills its cell; in the tree it keeps the fixed connector width. */
  fluid?: boolean;
  /** Boxes on the drill path are wider (w-80) so long goal texts stay shallow and the tiers
   * below remain in view. Safe for the connectors: parent and children are centred in the same
   * column, so the trunk stays at the parent's centre regardless of its width. */
  wide?: boolean;
  /** The drill path's parent box: readable but quieter than the focus node. */
  subdued?: boolean;
  /** Overview only: total knowledge beneath the sub-skills, appended to the count line. */
  deepKnowledge?: number | null;
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
      data-goal-id={node.goal.id}
      onClick={onClick}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onClick();
        }
      }}
      title={expandable ? "Unfold" : "View goal details"}
      className={`group relative flex cursor-pointer flex-col gap-1.5 rounded-lg border-t-4 border p-3 text-left shadow-sm transition ${
        fluid ? "" : wide ? "w-80 shrink-0" : "w-56 shrink-0"
      } ${
        isGap
          ? "border-hestia-danger/40 bg-[color-mix(in_srgb,var(--hestia-danger)_8%,var(--hestia-surface))] hover:border-hestia-danger"
          : "border-hestia-border bg-hestia-surface hover:border-hestia-primary hover:bg-hestia-bg"
      } ${dimmed ? "opacity-30" : subdued ? "opacity-80 hover:opacity-100" : ""}`}
      style={{
        borderTopColor: meta.color,
        // The focused box keeps only this quiet tint — the shelved siblings do the highlighting.
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
              {deepKnowledge != null && deepKnowledge > 0 && (
                <> · {deepKnowledge} knowledge</>
              )}
            </span>
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className={`h-3.5 w-3.5 transition-transform ${open ? "rotate-90" : ""}`}
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
 * Collapsed-tree schematic hanging under an overview card: connector stubs to unlabelled
 * sub-skill bars, then a fainter row of knowledge dots — a first-glance hint that three tiers
 * unfold beneath.
 */
function Stub({
  childCount,
  hasKnowledge,
}: {
  childCount: number;
  hasKnowledge: boolean;
}) {
  const xs =
    childCount >= 3 ? [26, 60, 94] : childCount === 2 ? [43, 77] : [60];
  const barColor = `color-mix(in srgb, ${COMPETENCY_ROLE_META["sub-skill"].color} 45%, var(--hestia-surface))`;
  const dotColor = `color-mix(in srgb, ${COMPETENCY_ROLE_META.knowledge.color} 60%, var(--hestia-surface))`;
  return (
    <div className="pointer-events-none" aria-hidden="true">
      <svg width={120} height={14} className="mx-auto block">
        <g
          stroke={`color-mix(in srgb, ${COMPETENCY_ROLE_META["sub-skill"].color} 55%, transparent)`}
          strokeWidth={1.5}
          fill="none"
          strokeLinecap="round"
        >
          {xs.map((x) =>
            x === 60 ? (
              <path key={x} d="M 60 0 V 14" />
            ) : (
              <path
                key={x}
                d={`M 60 0 V 4 Q 60 7 ${x < 60 ? 55 : 65} 7 H ${x < 60 ? x + 5 : x - 5} Q ${x} 7 ${x} 10 V 14`}
              />
            ),
          )}
        </g>
      </svg>
      <div className="flex justify-center gap-2.5">
        {xs.map((x) => (
          <span
            key={x}
            className="h-[7px] w-[34px] rounded"
            style={{ backgroundColor: barColor }}
          />
        ))}
      </div>
      {hasKnowledge && (
        <div className="mt-1 flex justify-center gap-[7px] opacity-50">
          {[0, 1, 2, 3, 4].map((i) => (
            <span
              key={i}
              className="h-1.5 w-1.5 rounded-full"
              style={{ backgroundColor: dotColor }}
            />
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * Mini leaf indicator under an unfocused sub-skill box: stub lines branching into a few
 * knowledge dots, hinting that another tier unfolds beneath it. Dots = knowledge, the same
 * visual language as the overview schematic; the count is suggestive (capped at three), the
 * exact number already sits in the box's "N items" line.
 */
function LeafStub({ count }: { count: number }) {
  const xs = count >= 3 ? [12, 30, 48] : count === 2 ? [21, 39] : [30];
  const lineColor = `color-mix(in srgb, ${COMPETENCY_ROLE_META.knowledge.color} 55%, transparent)`;
  const dotColor = `color-mix(in srgb, ${COMPETENCY_ROLE_META.knowledge.color} 55%, var(--hestia-surface))`;
  return (
    <div className="pointer-events-none" aria-hidden="true">
      <svg width={60} height={12} className="mx-auto block">
        <g
          stroke={lineColor}
          strokeWidth={1.5}
          fill="none"
          strokeLinecap="round"
        >
          {xs.map((x) =>
            x === 30 ? (
              <path key={x} d="M 30 0 V 12" />
            ) : (
              <path
                key={x}
                d={`M 30 0 V 3 Q 30 6 ${x < 30 ? 26 : 34} 6 H ${x < 30 ? x + 4 : x - 4} Q ${x} 6 ${x} 9 V 12`}
              />
            ),
          )}
        </g>
      </svg>
      <div className="mt-px flex justify-center gap-2">
        {xs.map((x) => (
          <span
            key={x}
            className="h-[7px] w-[7px] rounded-full"
            style={{ backgroundColor: dotColor }}
          />
        ))}
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
