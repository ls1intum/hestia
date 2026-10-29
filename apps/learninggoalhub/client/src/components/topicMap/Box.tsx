import type { ReactNode } from "react";
import type { LearningGoal } from "../../api/client.ts";
import { RenameField, RowAction } from "../GoalInlineEditing.tsx";
import { RoleBadge, AiInferredBadge, ManualBadge } from "../GoalBadges.tsx";
import {
  TREE_ROLE_META,
  displayedGoalLabel,
  tierNoun,
  type TreeNode,
  type TreeRole,
} from "../../lib/goals.ts";

/** The rename and delete actions shared by every box, the same as a table row's. */
export type GoalActions = {
  editingId: number | null;
  onStartEdit: (goal: LearningGoal) => void;
  onEndEdit: (goal: LearningGoal, text: string | null) => void;
  onDelete: (goal: LearningGoal) => void;
};

/** A readable skill/sub-skill/knowledge rectangle. Beneath its wording it shows the attributes the
 * table currently shows as columns, then what it holds by tier ("3 sub-skills"), with an unfold
 * chevron on branch boxes. Rename and delete float top-right on hover, as on a table row.
 * Clicking the body folds or unfolds a box that holds goals. A dimmed sibling keeps only its
 * wording and count, so the focused branch stays readable. It is a div (not a button) so
 * the action and attribute buttons can nest. */
export function Box({
  node,
  active,
  expandable,
  onClick,
  highlighted = false,
  actions,
  dimmed = false,
  compact = false,
  leaf = false,
  clampText = false,
  group = false,
  sequenceLabel,
  fullWording,
  attributes,
}: {
  node: TreeNode;
  /** The box is the focused, unfolded node. */
  active: boolean;
  expandable: boolean;
  /** Folds or unfolds the box; absent on a box with nothing to unfold. */
  onClick?: () => void;
  /** Outlined for a moment: the child a mini box was clicked for. */
  highlighted?: boolean;
  actions: GoalActions;
  /** An unfocused sibling in the visible row. */
  dimmed?: boolean;
  /** Shrinks a dimmed sibling to keep focused context rows compact. */
  compact?: boolean;
  /** Leaf-row width (w-60). */
  leaf?: boolean;
  /** Keeps sibling context compact without truncating the focused node. */
  clampText?: boolean;
  /** The "Additional sub-skills" box: no goal, so no tier badge, number or actions. */
  group?: boolean;
  /** Hierarchical lecture-order label, e.g. "2.3". */
  sequenceLabel?: string;
  fullWording: boolean;
  /** The goal's attributes in the table's visible columns. */
  attributes: ReactNode;
}) {
  const meta = TREE_ROLE_META[node.role];
  const outline = group ? "var(--hestia-text-muted)" : meta.color;
  const editing = actions.editingId === node.goal.id;
  const childCounts = new Map<TreeRole, number>();
  for (const child of node.children)
    childCounts.set(child.role, (childCounts.get(child.role) ?? 0) + 1);
  return (
    <div
      // The whole box is a mouse target; keyboard and screen readers use the count line's button,
      // since a button may not contain the box's own action buttons.
      onClick={onClick}
      data-goal-id={node.goal.id}
      className={`group relative flex ${onClick ? "cursor-pointer" : ""} flex-col gap-1.5 rounded-lg border-[1.5px] ${group ? "border-dashed" : ""} p-3 text-left transition ${
        leaf ? "w-60 shrink-0" : compact ? "w-40 shrink-0" : "w-56 shrink-0"
      } ${
        node.role === "knowledge"
          ? "border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_2.5%,var(--hestia-surface))] hover:border-hestia-primary hover:bg-hestia-bg"
          : "border-hestia-border bg-hestia-surface hover:border-hestia-primary hover:bg-hestia-bg"
      } ${dimmed ? "opacity-60 hover:opacity-100" : ""} ${
        highlighted ? "ring-2 ring-hestia-primary ring-offset-2 ring-offset-hestia-bg" : ""
      }`}
      style={{
        // The whole outline carries the role colour, so the tier reads without a shadow or rail.
        borderColor: outline,
        // The focused box keeps a quiet role tint while its row siblings remain subdued context.
        ...(active
          ? {
              backgroundColor: `color-mix(in srgb, ${outline} 12%, var(--hestia-surface))`,
            }
          : {}),
      }}
    >
      {!group && (
        <div className="flex min-w-0 items-center gap-1.5">
          <RoleBadge role={node.role} />
          {node.goal.creationProvenance === "WIZARD_AI_SUBTREE" && <AiInferredBadge compact />}
          {node.goal.creationProvenance === "USER_CREATED" && <ManualBadge />}
        </div>
      )}
      {group ? (
        <p className="text-sm leading-snug italic text-hestia-text-muted">
          {displayedGoalLabel(node.goal)}
        </p>
      ) : editing ? (
        <div className="flex min-w-0 items-start gap-1 text-sm">
          {sequenceLabel != null && (
            <span className="pt-0.5 tabular-nums text-hestia-text-muted">{sequenceLabel}</span>
          )}
          <RenameField
            text={node.goal.text ?? ""}
            onDone={(text) => actions.onEndEdit(node.goal, text)}
          />
        </div>
      ) : (
        <p
          className={`text-sm font-medium leading-snug text-hestia-text ${
            clampText ? "line-clamp-3" : ""
          }`}
        >
          {sequenceLabel != null && <span className="tabular-nums">{sequenceLabel} </span>}
          {displayedGoalLabel(node.goal, fullWording)}
        </p>
      )}
      {!editing && !group && (
        // Revealed on hover (or keyboard focus) like a table row's, floating over the box's corner.
        <span className="absolute right-1.5 top-1.5 z-[1] flex items-center gap-0.5 rounded-md border border-hestia-border bg-hestia-surface p-0.5 opacity-0 shadow-sm transition focus-within:opacity-100 group-hover:opacity-100">
          <RowAction
            label="Rename goal"
            onClick={() => actions.onStartEdit(node.goal)}
            className="hover:bg-hestia-primary-muted hover:text-hestia-text"
          >
            <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
          </RowAction>
          <RowAction
            label="Delete goal"
            onClick={() => actions.onDelete(node.goal)}
            className="hover:bg-hestia-danger hover:text-hestia-on-danger"
          >
            <path d="M4 6h12M8 6V4h4v2M6 6l1 10h6l1-10" />
          </RowAction>
        </span>
      )}
      {!dimmed && attributes}
      {childCounts.size > 0 && (
        <CountLine
          counts={[...childCounts]
            .map(([role, count]) => `${count} ${tierNoun(role, count)}`)
            .join(" · ")}
          expanded={active}
          onToggle={expandable ? onClick : undefined}
        />
      )}
    </div>
  );
}

/**
 * The box's child counts. In an expandable box it is the button that unfolds the box: the box itself
 * cannot be a button, because it holds the rename and delete buttons.
 */
function CountLine({
  counts,
  expanded,
  onToggle,
}: {
  counts: string;
  expanded: boolean;
  onToggle?: () => void;
}) {
  const line = "mt-auto flex items-center gap-1 pt-1 text-xs text-hestia-text-muted";
  if (!onToggle) {
    return (
      <div className={line}>
        <span className="tabular-nums">{counts}</span>
      </div>
    );
  }
  return (
    <button
      type="button"
      aria-expanded={expanded}
      onClick={(e) => {
        e.stopPropagation();
        onToggle();
      }}
      className={`${line} rounded text-left hover:text-hestia-text`}
    >
      <span className="tabular-nums">{counts}</span>
      <svg
        viewBox="0 0 20 20"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={`h-3.5 w-3.5 transition-transform ${expanded ? "rotate-90" : ""}`}
      >
        <path d="M7 5l6 5-6 5" />
      </svg>
    </button>
  );
}

/**
 * Mini leaf indicator under a dimmed context box: stub lines branching into a few dots,
 * hinting that another tier unfolds beneath it. The count is suggestive (capped at three); the
 * exact numbers already sit in the box's count line.
 */
export function LeafStub({ count }: { count: number }) {
  const xs = count >= 3 ? [12, 30, 48] : count === 2 ? [21, 39] : [30];
  const lineColor = `color-mix(in srgb, ${TREE_ROLE_META.knowledge.color} 55%, transparent)`;
  const dotColor = `color-mix(in srgb, ${TREE_ROLE_META.knowledge.color} 55%, var(--hestia-surface))`;
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
