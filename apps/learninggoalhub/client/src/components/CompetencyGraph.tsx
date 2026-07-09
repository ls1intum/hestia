import { useEffect, useMemo, useRef, useState } from "react";
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
 * card moves to the centre with its sub-skills fanned out below it, while its siblings shrink
 * into a chip shelf above for one-click switching. Click a sub-skill and the same happens one
 * tier down — its sibling sub-skills gather into a second shelf that sits at its own tree depth,
 * between the parent skill and the focused sub-skill, with short trunk segments keeping the
 * skill → sub-skill edge continuous. The centre column is always the drill path. The whole tree
 * shares one horizontal scroll container so boxes and connectors stay aligned; each focus change
 * re-centres it. Clicking a leaf, or the ⓘ on a branch box, opens the goal detail modal.
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

  // Keep the drill path (the centre column) in view: when the tree is wider than the viewport it
  // scrolls, and a fresh focus should start centred on the focused node, not at the left edge.
  const scrollRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollLeft = (el.scrollWidth - el.clientWidth) / 2;
  }, [path]);

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

  // Overview: every competency as a collapsed tree in a wrapped grid.
  if (!competency) {
    return (
      <div className="flex flex-col gap-3 pt-1">
        <p className="px-1 text-sm text-hestia-text-muted">
          Click a skill to focus its tree — sub-skills unfold below it, the
          other skills move to a shelf above.
        </p>
        <div className="grid gap-3 px-1 pb-2 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {forest.map((node) => {
            const expandable = node.children.length > 0;
            return (
              <Box
                key={node.goal.id}
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
                deepKnowledge={countGrandchildren(node)}
                stub
              />
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
    <div className="flex flex-col gap-2 px-1 pt-1">
      <Shelf
        label="Other skills"
        color={skillColor}
        items={otherSkills}
        onPick={pickCompetency}
        onOpenDetail={onOpenDetail}
        highlight={highlight}
      />
      <div ref={scrollRef} className="overflow-x-auto pb-2">
        <div
          key={`tree-${competency.goal.id}-${subSkill?.goal.id ?? "none"}`}
          className="comp-unfold mx-auto flex w-max min-w-full flex-col items-center"
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
                {competency.children.map((child) => {
                  const expandable = child.children.length > 0;
                  return (
                    <Box
                      key={child.goal.id}
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
                onClick={() => setPath([competency.goal.id!])}
                onOpenDetail={() => onOpenDetail(competency.goal)}
                actions={actions}
                dimmed={isDimmed(competency, highlight)}
                subdued
                wide
              />
              {otherSubs.length > 0 ? (
                <>
                  {/* Sibling sub-skills sit at their own tree depth, between parent and focus;
                      the trunk segments keep the skill → sub-skill edge readable through them. */}
                  <VLine color={skillColor} />
                  <Shelf
                    label="Other sub-skills"
                    color={subColor}
                    items={otherSubs}
                    onPick={pickSubSkill}
                    onOpenDetail={onOpenDetail}
                    highlight={highlight}
                    between
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
                {subSkill.children.map((leaf) => (
                  <Box
                    key={leaf.goal.id}
                    node={leaf}
                    active={false}
                    expandable={false}
                    onClick={() => onOpenDetail(leaf.goal)}
                    onOpenDetail={() => onOpenDetail(leaf.goal)}
                    actions={actions}
                    dimmed={isDimmed(leaf, highlight)}
                    wide
                  />
                ))}
              </div>
            </>
          )}
        </div>
      </div>
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
 * Sibling shelf: the nodes not currently focused at a tier, shrunk to one-click chips. The top
 * shelf holds the other skills; with a sub-skill focused, a second shelf holds its sibling
 * sub-skills and sits `between` the parent skill and the focus node — at its own tree depth.
 */
function Shelf({
  label,
  color,
  items,
  onPick,
  onOpenDetail,
  highlight,
  between = false,
}: {
  label: string;
  color: string;
  items: CompetencyNode[];
  onPick: (id: number) => void;
  onOpenDetail: (goal: LearningGoal) => void;
  highlight: "approved" | "unapproved" | null;
  between?: boolean;
}) {
  if (items.length === 0) return null;
  return (
    <div
      className={`flex flex-wrap items-center gap-1.5 rounded-xl border border-dashed px-2.5 py-1.5 ${
        between ? "max-w-[44rem] justify-center" : ""
      }`}
      style={{
        borderColor: `color-mix(in srgb, ${color} 45%, transparent)`,
        backgroundColor: `color-mix(in srgb, ${color} 4%, transparent)`,
      }}
    >
      <span
        className="mr-1 whitespace-nowrap text-[0.6rem] font-semibold uppercase tracking-wider"
        style={{ color: `color-mix(in srgb, ${color} 80%, var(--hestia-text))` }}
      >
        {label}
      </span>
      {items.map((node) => (
        // Chips truncate, so each carries a styled tooltip with the full goal text (same look
        // as the BoxAction tooltips; instant, unlike the browser's native title delay).
        <span key={node.goal.id} className="group/chip relative inline-flex">
          <button
            type="button"
            onClick={() =>
              node.children.length > 0
                ? onPick(node.goal.id!)
                : onOpenDetail(node.goal)
            }
            className={`max-w-52 truncate rounded-full border border-hestia-border bg-hestia-surface py-1 pl-2 pr-2.5 text-xs font-medium shadow-sm transition hover:shadow ${
              isDimmed(node, highlight)
                ? "opacity-30"
                : "opacity-80 hover:opacity-100"
            }`}
            style={{ borderLeftWidth: 3, borderLeftColor: color }}
          >
            {node.goal.text}
          </button>
          <span
            role="tooltip"
            className="pointer-events-none absolute left-1/2 top-full z-30 mt-1.5 hidden w-64 -translate-x-1/2 rounded-lg border border-hestia-border border-l-[3px] bg-hestia-surface p-2 text-left text-[0.7rem] font-normal leading-snug text-hestia-text shadow-lg group-hover/chip:block"
            style={{ borderLeftColor: color }}
          >
            {node.goal.text}
          </span>
        </span>
      ))}
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

  return (
    <svg
      width={width}
      height={CONNECTOR_H}
      className="block shrink-0"
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
                d={`M ${trunk} 0 C ${trunk} ${mid} ${cx} ${mid} ${cx} ${CONNECTOR_H}`}
              />
            );
          }
          const sg = dx > 0 ? 1 : -1;
          return (
            <path
              key={i}
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
  stub = false,
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
  /** Overview only: render the collapsed-tree schematic under the counts. */
  stub?: boolean;
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
      {stub && childCount > 0 && (
        <Stub childCount={childCount} hasKnowledge={(deepKnowledge ?? 0) > 0} />
      )}
    </div>
  );
}

/**
 * Collapsed-tree schematic on an overview card: connector stubs to unlabelled sub-skill bars,
 * then a fainter row of knowledge dots — a first-glance hint that three tiers unfold beneath.
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
    <div className="pointer-events-none mt-0.5" aria-hidden="true">
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
