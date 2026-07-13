import { useMemo, useState } from "react";
import type { LearningGoal } from "../api/client.ts";
import {
  COMPETENCY_ROLE_META,
  buildCompetencyForest,
  type CompetencyNode,
} from "../lib/goals.ts";

/**
 * Collapsible top-down rendering of the competency tree: terminal competencies → sub-skills →
 * knowledge/gap leaves. Gap leaves (knowledge the course does not yet cover) are styled in the
 * danger colour so an instructor spots them at a glance. Clicking a node opens the goal detail
 * modal. Each node carries a badge with its direct child count.
 */
export default function CompetencyTree({
  goals,
  onOpenDetail,
}: {
  goals: LearningGoal[];
  onOpenDetail: (goal: LearningGoal) => void;
}) {
  const forest = useMemo(() => buildCompetencyForest(goals), [goals]);

  // Ids of expanded nodes. Default: competencies open (their sub-skills visible), deeper collapsed.
  const [expanded, setExpanded] = useState<Set<number>>(
    () => new Set(forest.map((n) => n.goal.id!).filter((id) => id != null)),
  );

  const allIds = useMemo(() => collectIds(forest), [forest]);
  const allOpen = expanded.size === allIds.length && allIds.length > 0;

  const toggle = (id: number) =>
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  if (forest.length === 0) {
    return (
      <p className="rounded-xl border border-dashed border-hestia-border p-8 text-center text-sm text-hestia-text-muted">
        No competency tree for this course yet. Re-run the extraction to synthesise terminal
        competencies, sub-skills and knowledge gaps.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Legend />
        <button
          type="button"
          onClick={() =>
            setExpanded(allOpen ? new Set() : new Set(allIds))
          }
          className="text-sm font-medium text-hestia-primary transition hover:text-hestia-primary-hover"
        >
          {allOpen ? "Collapse all" : "Expand all"}
        </button>
      </div>
      <ul className="flex flex-col gap-1.5">
        {forest.map((node) => (
          <TreeNode
            key={node.goal.id}
            node={node}
            depth={0}
            expanded={expanded}
            onToggle={toggle}
            onOpenDetail={onOpenDetail}
          />
        ))}
      </ul>
    </div>
  );
}

function TreeNode({
  node,
  depth,
  expanded,
  onToggle,
  onOpenDetail,
}: {
  node: CompetencyNode;
  depth: number;
  expanded: Set<number>;
  onToggle: (id: number) => void;
  onOpenDetail: (goal: LearningGoal) => void;
}) {
  const id = node.goal.id!;
  const hasChildren = node.children.length > 0;
  const isOpen = expanded.has(id);
  const meta = COMPETENCY_ROLE_META[node.role];
  const isGap = node.role === "gap";

  return (
    <li>
      <div
        role="button"
        tabIndex={0}
        onClick={() => onOpenDetail(node.goal)}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            onOpenDetail(node.goal);
          }
        }}
        className={`group flex cursor-pointer items-start gap-2 rounded-lg border p-2.5 text-left shadow-sm transition ${
          isGap
            ? "border-hestia-danger/40 bg-[color-mix(in_srgb,var(--hestia-danger)_8%,transparent)] hover:border-hestia-danger"
            : "border-hestia-border bg-hestia-surface hover:border-hestia-primary hover:bg-hestia-bg"
        }`}
      >
        {/* Expand/collapse — only when there are children. A spacer keeps texts aligned otherwise. */}
        {hasChildren ? (
          <button
            type="button"
            aria-label={isOpen ? "Collapse" : "Expand"}
            aria-expanded={isOpen}
            onClick={(e) => {
              e.stopPropagation();
              onToggle(id);
            }}
            className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded text-hestia-text-muted transition hover:bg-hestia-primary-muted hover:text-hestia-text"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className={`h-3.5 w-3.5 transition-transform ${isOpen ? "rotate-90" : ""}`}
            >
              <path d="M7 5l6 5-6 5" />
            </svg>
          </button>
        ) : (
          <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center">
            <span
              className="h-1.5 w-1.5 rounded-full"
              style={{ backgroundColor: meta.color }}
            />
          </span>
        )}

        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span
              className="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[0.65rem] font-semibold uppercase tracking-wide"
              style={{
                color: meta.color,
                backgroundColor: `color-mix(in srgb, ${meta.color} 15%, transparent)`,
              }}
            >
              {isGap && <GapIcon />}
              {meta.label}
            </span>
            {node.goal.bloomLevel && (
              <span className="inline-flex items-center rounded-full bg-[color-mix(in_srgb,var(--hestia-accent)_15%,transparent)] px-2 py-0.5 text-[0.65rem] font-semibold uppercase tracking-wide text-hestia-accent">
                {titleCase(node.goal.bloomLevel)}
              </span>
            )}
            {hasChildren && (
              <span className="text-xs tabular-nums text-hestia-text-muted">
                {node.children.length}{" "}
                {node.role === "competency" ? "sub-skill" : "item"}
                {node.children.length === 1 ? "" : "s"}
              </span>
            )}
          </div>
          <p
            className={`mt-1 leading-relaxed ${
              depth === 0
                ? "text-sm font-semibold text-hestia-text"
                : "text-sm text-hestia-text"
            } ${isGap ? "text-hestia-danger" : ""}`}
          >
            {node.goal.text}
          </p>
        </div>
      </div>

      {hasChildren && isOpen && (
        <ul className="mt-1.5 flex flex-col gap-1.5 border-l border-hestia-border pl-3 ml-2.5">
          {node.children.map((child) => (
            <TreeNode
              key={child.goal.id}
              node={child}
              depth={depth + 1}
              expanded={expanded}
              onToggle={onToggle}
              onOpenDetail={onOpenDetail}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

/** Colour-key for the four node roles, with the gap role called out as "not yet covered". */
function Legend() {
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-hestia-text-muted">
      {(
        ["competency", "sub-skill", "knowledge", "gap"] as const
      ).map((role) => {
        const meta = COMPETENCY_ROLE_META[role];
        return (
          <span key={role} className="inline-flex items-center gap-1.5">
            <span
              className="h-2 w-2 rounded-full"
              style={{ backgroundColor: meta.color }}
            />
            {role === "gap" ? "Gap (not yet covered)" : meta.label}
          </span>
        );
      })}
    </div>
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

/** Title-cases an ALL-CAPS enum value (e.g. "UNDERSTAND" → "Understand"). */
function titleCase(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

/** All node ids in the forest (depth-first), for the expand/collapse-all toggle. */
function collectIds(forest: CompetencyNode[]): number[] {
  const ids: number[] = [];
  const walk = (nodes: CompetencyNode[]) => {
    for (const n of nodes) {
      if (n.goal.id != null && n.children.length > 0) ids.push(n.goal.id);
      walk(n.children);
    }
  };
  walk(forest);
  return ids;
}
