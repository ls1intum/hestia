import type { LearningGoal } from "../../api/client.ts";
import CoverageBadge from "../CoverageBadge.tsx";
import {
  TREE_ROLE_META,
  coverageOf,
  displayedGoalLabel,
  tierNoun,
  type TreeRole,
} from "../../lib/goals.ts";
import {
  LEVEL_SCALES,
  isGrouping,
  kindLabel,
  type Row,
  type LevelScale,
  type GoalChanges,
  type AttributeKey,
} from "./model.ts";
import { TierDot, KindChip, SourceChip } from "./Chips.tsx";
import { LevelCell } from "./LevelCell.tsx";

/**
 * The row that gathers a topic's goals in no skill. It is no goal: it has no number, levels or
 * actions, only a fold, and while folded it counts what it holds like a skill does.
 */
export function LooseGroupRow({
  id,
  depth,
  zebra,
  open,
  context,
  items,
  gridCols,
  columns,
  onToggle,
}: {
  id: number;
  depth: number;
  zebra: boolean;
  open: boolean;
  context: boolean;
  items: Row[];
  gridCols: string;
  columns: AttributeKey[];
  onToggle: (id: number) => void;
}) {
  return (
    <div
      role="row"
      data-row-id={id}
      tabIndex={0}
      aria-expanded={open}
      onClick={() => {
        if (!window.getSelection()?.toString()) onToggle(id);
      }}
      onKeyDown={(e) => {
        if (e.target !== e.currentTarget) return;
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onToggle(id);
        }
      }}
      className={`grid cursor-pointer items-stretch border-b border-hestia-border/60 transition hover:bg-[color-mix(in_srgb,var(--hestia-primary)_7%,transparent)] ${
        zebra ? "bg-hestia-text/3" : ""
      } ${context ? "opacity-45" : ""}`}
      style={{ gridTemplateColumns: gridCols }}
    >
      <div role="gridcell" className="min-w-0 px-2.5 py-1.5">
        <div className="flex items-start gap-1">
          <span className="shrink-0" style={{ width: depth * 20 }} />
          {/* Dotted where a skill's rail is solid: a grouping, not a goal. */}
          <span
            aria-hidden="true"
            className="mr-1 shrink-0 self-stretch border-l-[3px] border-dotted"
            style={{ borderColor: TREE_ROLE_META.subSkill.color }}
          />
          <button
            type="button"
            aria-label={open ? "Collapse" : "Expand"}
            aria-expanded={open}
            onClick={(e) => {
              e.stopPropagation();
              onToggle(id);
            }}
            className="flex h-5 w-5 shrink-0 items-center justify-center rounded-sm text-hestia-text-muted transition hover:bg-hestia-primary-muted hover:text-hestia-text"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.5"
              strokeLinecap="round"
              strokeLinejoin="round"
              className={`h-3 w-3 transition-transform ${open ? "rotate-90" : ""}`}
            >
              <path d="M7 5l6 5-6 5" />
            </svg>
          </button>
          <span className="min-w-0 pt-px text-sm leading-relaxed text-hestia-text-muted">
            <span className="italic">Additional sub-skills</span>
            {!open && <ChildPreview role="skill" items={items} fullWording={false} />}
          </span>
        </div>
      </div>
      {columns.map((key) => (
        <div key={key} role="gridcell" />
      ))}
    </div>
  );
}

/**
 * What a collapsed row holds. A topic lists its first children, one per line with a dot in their
 * tier colour; a skill or sub-skill only counts its children by tier ("• 7 sub-skills"), which says
 * how big the branch is without making an opened topic much taller.
 */
export function ChildPreview({
  role,
  items,
  fullWording,
}: {
  role: TreeRole;
  items: Row[];
  fullWording: boolean;
}) {
  if (role !== "topic") {
    const counts = new Map<TreeRole, number>();
    for (const item of items) counts.set(item.role, (counts.get(item.role) ?? 0) + 1);
    return (
      <span className="flex flex-wrap gap-x-2.5 text-xs font-normal text-hestia-text-muted">
        {[...counts].map(([childRole, count]) => (
          <span key={childRole} className="inline-flex items-center gap-1.5 whitespace-nowrap">
            <TierDot role={childRole} />
            {count} {tierNoun(childRole, count)}
          </span>
        ))}
      </span>
    );
  }
  const shown = items.slice(0, 3);
  const rest = items.length - shown.length;
  return (
    <span className="my-0.5 grid gap-px text-xs font-normal text-hestia-text-muted">
      {shown.map((child) => (
        <span key={child.id} className="flex min-w-0 items-center gap-1.5">
          <TierDot role={child.role} />
          <span className="truncate">{displayedGoalLabel(child.goal, fullWording)}</span>
        </span>
      ))}
      {rest > 0 && <span className="pl-3 text-hestia-text-muted/80">+{rest} more</span>}
    </span>
  );
}

/**
 * The table's visible attribute columns laid out for a diagram box: coverage and kind as chips,
 * Bloom and SOLO side by side under their names, and the source beneath. A column hidden in the
 * table is hidden here too; the levels and the source stay editable and openable as in a row.
 */
export function DiagramAttributes({
  row,
  columns,
  levelFlag,
  openLevel,
  onToggleLevel,
  onCloseLevel,
  onUpdate,
  sourceOpen,
  onOpenSource,
  members,
}: {
  row: Row;
  columns: AttributeKey[];
  levelFlag?: string;
  openLevel: LevelScale | null;
  onToggleLevel: (row: Row, scale: LevelScale) => void;
  onCloseLevel: () => void;
  onUpdate: (goalId: number, changes: GoalChanges) => void;
  sourceOpen: boolean;
  onOpenSource: (row: Row, trigger: HTMLElement) => void;
  /** A skill's sub-skills, which its generated name sums up. */
  members?: Row[];
}) {
  const coverage = coverageOf(row.goal);
  const showCoverage = columns.includes("coverage") && coverage != null && coverage !== "unknown";
  const showKind = columns.includes("kind") && kindLabel(row) != null;
  // A topic is a noun phrase with no level of its own.
  const scales = (["bloom", "solo"] as const).filter(
    (scale) => columns.includes(scale) && row.role !== "topic",
  );
  const showSource =
    columns.includes("source") &&
    (row.goal.sources?.[0] != null ||
      row.goal.examTask != null ||
      (isGrouping(row.role) && row.goal.creationProvenance !== "USER_CREATED"));
  if (!showCoverage && !showKind && scales.length === 0 && !showSource) return null;
  return (
    <div className="flex flex-col gap-2 border-t border-hestia-border/60 pt-2">
      {(showCoverage || showKind) && (
        <div className="flex flex-wrap items-center gap-1">
          {showCoverage && <CoverageBadge goal={row.goal} flag={levelFlag} />}
          {showKind && <KindChip row={row} />}
        </div>
      )}
      {scales.length > 0 && (
        <div className="grid grid-cols-2 gap-2">
          {scales.map((scale) => (
            <div key={scale} className="flex min-w-0 flex-col items-start gap-0.5">
              <span className="text-[10px] font-semibold uppercase tracking-wide text-hestia-text-muted">
                {LEVEL_SCALES[scale].label}
              </span>
              <LevelCell
                scale={scale}
                value={scale === "bloom" ? row.goal.bloomLevel : row.goal.soloLevel}
                interactive
                open={openLevel === scale}
                onToggle={() => onToggleLevel(row, scale)}
                onClose={onCloseLevel}
                onSelect={(value) =>
                  onUpdate(
                    row.id,
                    scale === "bloom"
                      ? { bloomLevel: value as LearningGoal["bloomLevel"] }
                      : { soloLevel: value as LearningGoal["soloLevel"] },
                  )
                }
              />
            </div>
          ))}
        </div>
      )}
      {showSource && (
        <div className="flex min-w-0 text-xs text-hestia-text-muted">
          <SourceChip
            row={row}
            members={members}
            inline
            open={sourceOpen}
            onOpen={(trigger) => onOpenSource(row, trigger)}
          />
        </div>
      )}
    </div>
  );
}
