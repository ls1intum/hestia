import { useState, type KeyboardEvent as ReactKeyboardEvent, type ReactElement } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, type LearningGoal } from "../../api/client.ts";
import CompetencyCreationField from "../CompetencyCreationField.tsx";
import CoverageBadge from "../CoverageBadge.tsx";
import { RenameField, RowAction } from "../GoalInlineEditing.tsx";
import { displayedGoalLabel, type CoverageCounts } from "../../lib/goals.ts";
import {
  tierRailColor,
  type Row,
  type LevelScale,
  type GoalChanges,
  type Layout,
  type AttributeKey,
} from "./model.ts";
import { ChildPreview } from "./DiagramRows.tsx";
import { TopicCoverage, KindChip, SourceChip, TierChip } from "./Chips.tsx";
import { LevelCell } from "./LevelCell.tsx";

export function AppendKnob({
  depth,
  label,
  placeholder,
  color,
  last,
  active,
  value,
  pending,
  error,
  onStart,
  onChange,
  onSubmit,
  onCancel,
  onGenerate,
  generating,
  onFind,
}: {
  depth: number;
  label: string;
  placeholder: string;
  color: string;
  /** Knob in the trailing stack at the grid's bottom edge: its tooltip has to open upward. */
  last: boolean;
  active: boolean;
  value: string;
  pending: boolean;
  error?: string;
  onStart: () => void;
  onChange: (value: string) => void;
  onSubmit: () => void;
  onCancel: () => void;
  onGenerate?: () => void;
  generating?: boolean;
  onFind?: () => void;
}) {
  const left = `calc(0.625rem + ${depth * 20}px + 0.25rem + 1.5px - 1.2rem)`;
  return (
    <div
      className={`competency-append-container ${
        active ? "competency-append-container-active" : ""
      }`}
    >
      {active ? (
        // In flow rather than absolutely positioned: the grid scrolls inside a capped-height
        // container, which would clip a floating form opened on the last row.
        <CompetencyCreationField
          value={value}
          placeholder={placeholder}
          error={error}
          pending={pending}
          onChange={onChange}
          onSubmit={onSubmit}
          onCancel={onCancel}
          onGenerate={onGenerate}
          generating={generating}
          onFind={onFind}
          className="competency-append-form"
          style={{ marginLeft: `calc(0.625rem + ${depth * 20}px)` }}
        />
      ) : (
        <button
          type="button"
          aria-label={label}
          className="competency-append-button"
          style={{ left, color }}
          disabled={pending}
          onClick={onStart}
        >
          <span
            aria-hidden="true"
            className="competency-append-dot"
            style={{ backgroundColor: color }}
          />
          <span
            role="tooltip"
            className={`competency-append-label ${last ? "competency-append-label-above" : ""}`}
          >
            {label}
          </span>
        </button>
      )}
    </div>
  );
}

export function GridRow({
  row,
  depth,
  zebra,
  context,
  filtering,
  layout,
  open,
  childCount,
  preview,
  mapOpen,
  gridCols,
  columns,
  stickyTop,
  onToggle,
  onToggleMap,
  sourceOpen,
  onOpenSource,
  members,
  openLevel,
  onToggleLevel,
  onCloseLevel,
  fullWording,
  editing,
  onStartEdit,
  onEndEdit,
  onUpdate,
  onDelete,
  levelFlag,
  coverage,
  addChildLabel,
  onAddChild,
  spacedTop,
}: {
  row: Row;
  depth: number;
  zebra: boolean;
  context: boolean;
  filtering: boolean;
  layout: Layout;
  /** Table layout: the row's branch is unfolded. */
  open: boolean;
  /** Children this row renders in the table layout, which decides the caret. */
  childCount: number;
  /** Map layout: this topic's map is open beneath the row, which pins the row under the header. */
  mapOpen: boolean;
  gridCols: string;
  /** The attribute columns shown after the learning goal, in their order. */
  columns: AttributeKey[];
  stickyTop: number;
  onToggle: (id: number) => void;
  onToggleMap: (id: number) => void;
  /** This row's source is the one shown in the PDF panel. */
  sourceOpen: boolean;
  onOpenSource: (row: Row, trigger: HTMLElement) => void;
  /** A skill's sub-skills, which its generated name sums up. */
  members?: Row[];
  /** The taxonomy whose level menu is open on this row, if any. */
  openLevel: LevelScale | null;
  onToggleLevel: (row: Row, scale: LevelScale) => void;
  onCloseLevel: () => void;
  /** Show the goal's full wording rather than its short label. */
  fullWording: boolean;
  /** The goal's wording is being renamed in place. */
  editing: boolean;
  onStartEdit: (row: Row) => void;
  onEndEdit: () => void;
  onUpdate: (goalId: number, changes: GoalChanges) => void;
  onDelete: (goal: LearningGoal) => void;
  /** Why this sub-skill's exercise level looks abnormal against its topic's lectures, if it does. */
  levelFlag?: string;
  /** A topic's sub-skills by lecture and exercise coverage. */
  coverage?: CoverageCounts;
  /** The children of a collapsed topic, skill or sub-skill, previewed beneath its wording. */
  preview?: Row[];
  /** Names the "+" action; absent when this row takes no children here. */
  addChildLabel?: string;
  onAddChild?: () => void;
  /** Sets this row off from the one above it, which both layouts use between topics. */
  spacedTop?: boolean;
}) {
  const interactive = !context;
  // A filtered list folds in either layout; browsing folds only in the table.
  const canToggle = childCount > 0 && (layout === "table" || filtering);
  // While browsing the diagram layout, a topic row opens its diagram.
  const opensMap = layout === "diagram" && row.role === "topic" && !filtering;
  // Anywhere else a click on a row with children folds it, as the chevron does; the row's own
  // controls (level, source, rename, delete, +) keep their clicks to themselves. A row being
  // renamed doesn't fold.
  const activate = opensMap
    ? () => onToggleMap(row.id)
    : canToggle && !editing
      ? () => onToggle(row.id)
      : null;
  // A context row still folds, so a filtered list can be tidied from its dimmed parents too.
  const clickable = activate != null && (interactive || canToggle);
  // Role-tinted rail beside the name, so the tier reads at a glance; knowledge is faded so the
  // branch tiers (topic / skill / sub-skill) and gaps stand out.
  const railColor = tierRailColor(row.role);
  // The attribute cells by column, so they render in the reader's column order.
  const cells = {
    role: (
      <div key="role" role="gridcell" className="flex min-w-0 items-start overflow-hidden px-2 py-1.5">
        <TierChip role={row.role} />
      </div>
    ),
    coverage: (
      <div key="coverage" role="gridcell" className="flex min-w-0 items-start overflow-hidden px-2 py-1.5">
        {coverage ? (
          <TopicCoverage coverage={coverage} />
        ) : (
          // Only a goal with a source of its own has a coverage; the badge renders nothing otherwise.
          <CoverageBadge goal={row.goal} flag={levelFlag} size="cell" />
        )}
      </div>
    ),
    kind: (
      <div key="kind" role="gridcell" className="flex min-w-0 items-start overflow-hidden px-2 py-1.5">
        <KindChip row={row} />
      </div>
    ),
    ...Object.fromEntries(
      (["bloom", "solo"] as const).map((scale) => [
        scale,
        <div key={scale} role="gridcell" className="flex min-w-0 items-start overflow-hidden px-2 py-1.5">
          <LevelCell
            scale={scale}
            value={scale === "bloom" ? row.goal.bloomLevel : row.goal.soloLevel}
            // A topic is a noun phrase with no level of its own, so its cells are not editable.
            interactive={interactive && row.role !== "topic"}
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
        </div>,
      ]),
    ),
    source: (
      <div
        key="source"
        role="gridcell"
        className="flex min-w-0 items-start overflow-hidden px-2 py-1.5 text-xs text-hestia-text-muted"
      >
        <SourceChip
          row={row}
          members={members}
          disabled={context}
          open={sourceOpen}
          onOpen={(trigger) => onOpenSource(row, trigger)}
        />
      </div>
    ),
  } as Record<AttributeKey, ReactElement>;
  return (
    <div
      role="row"
      data-row-id={row.id}
      {...(clickable
        ? {
            tabIndex: 0,
            onClick: () => {
              // Selecting a goal's wording to copy it is not a request to fold the row.
              if (window.getSelection()?.toString()) return;
              activate();
            },
            onKeyDown: (e: ReactKeyboardEvent) => {
              if (e.target !== e.currentTarget) return;
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                activate();
              }
            },
          }
        : {})}
      {...(opensMap ? { "aria-expanded": mapOpen } : {})}
      className={`group grid items-stretch border-b border-hestia-border/60 transition ${
        spacedTop ? "mt-2 " : ""
      }${
        mapOpen
          ? "bg-[color-mix(in_srgb,var(--hestia-primary)_10%,var(--hestia-surface))] shadow-[0_6px_14px_-10px_rgba(0,0,0,0.35)]"
          : zebra
            ? "bg-hestia-text/3"
            : ""
      } ${
        context
          ? `opacity-45${clickable ? " cursor-pointer" : ""}`
          : mapOpen
            ? "cursor-pointer"
            : `${activate ? "cursor-pointer " : ""}hover:bg-[color-mix(in_srgb,var(--hestia-primary)_7%,transparent)]`
      }`}
      style={{
        gridTemplateColumns: gridCols,
        // The sticky row paints over the map beneath it, so its background above is opaque.
        ...(mapOpen ? { position: "sticky", top: stickyTop, zIndex: 5 } : {}),
      }}
    >
      <div role="gridcell" className="relative min-w-0 px-2.5 py-1.5">
        <div className="flex items-start gap-1">
          <span className="shrink-0" style={{ width: depth * 20 }} />
          <span
            aria-hidden="true"
            className="mr-1 w-[3px] shrink-0 self-stretch rounded-full"
            style={{ backgroundColor: railColor }}
          />
          {canToggle ? (
            <button
              type="button"
              aria-label={open ? "Collapse" : "Expand"}
              aria-expanded={open}
              onClick={(e) => {
                e.stopPropagation();
                onToggle(row.id);
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
          ) : opensMap ? (
            // The whole row is the control; the chevron only shows whether its map is open.
            <span
              aria-hidden="true"
              className={`flex h-5 w-5 shrink-0 items-center justify-center ${
                mapOpen ? "text-hestia-primary" : "text-hestia-text-muted"
              }`}
            >
              <svg
                viewBox="0 0 20 20"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
                className={`h-3 w-3 transition-transform ${mapOpen ? "rotate-90" : ""}`}
              >
                <path d="M7 5l6 5-6 5" />
              </svg>
            </span>
          ) : (
            <span className="h-5 w-5 shrink-0" aria-hidden="true" />
          )}
          {editing ? (
            <span className="flex min-w-0 flex-1 items-start gap-1 pt-px text-sm leading-relaxed">
              <span className="tabular-nums text-hestia-text-muted">{row.number}.</span>
              <RenameField
                text={row.goal.text ?? ""}
                onDone={(text) => {
                  if (text != null) onUpdate(row.id, { text });
                  onEndEdit();
                }}
              />
            </span>
          ) : (
          <span
            className={`min-w-0 break-words pt-px text-sm leading-relaxed text-hestia-text ${
              row.role === "topic" ? "font-semibold" : ""
            }`}
          >
            <span className="mr-1 tabular-nums text-hestia-text-muted">{row.number}.</span>
            {/* A short label keeps its full wording reachable as a tooltip. */}
            <span
              title={
                !fullWording && row.goal.shortLabel && row.goal.text !== row.goal.shortLabel
                  ? row.goal.text
                  : undefined
              }
            >
              {displayedGoalLabel(row.goal, fullWording)}
            </span>
            {preview && <ChildPreview role={row.role} items={preview} fullWording={fullWording} />}
          </span>
          )}
          {interactive && !editing && (
            // Revealed on hover (or keyboard focus), so resting rows read as plain text. They float over
            // the end of the wording instead of reserving its width, which a narrow column needs.
            <span className="absolute right-1.5 top-1 z-[1] flex items-center gap-0.5 rounded-md border border-hestia-border bg-hestia-surface p-0.5 opacity-0 shadow-sm transition focus-within:opacity-100 group-hover:opacity-100">
              {onAddChild && addChildLabel && (
                <RowAction
                  label={addChildLabel}
                  onClick={onAddChild}
                  className="hover:bg-hestia-primary-muted hover:text-hestia-text"
                >
                  <path d="M10 5v10M5 10h10" />
                </RowAction>
              )}
              <RowAction
                label="Rename goal"
                onClick={() => onStartEdit(row)}
                className="hover:bg-hestia-primary-muted hover:text-hestia-text"
              >
                <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
              </RowAction>
              <RowAction
                label="Delete goal"
                onClick={() => onDelete(row.goal)}
                className="hover:bg-hestia-danger hover:text-hestia-on-danger"
              >
                <path d="M4 6h12M8 6V4h4v2M6 6l1 10h6l1-10" />
              </RowAction>
            </span>
          )}
        </div>
      </div>
      {columns.map((key) => cells[key])}
    </div>
  );
}

/**
 * The session a source sits in, renamable in place from the PDF panel. A session is shared by every
 * goal extracted from it, so the field says the rename reaches all of them rather than passing for a
 * per-row edit. Enter saves, Escape cancels.
 */
export function SessionName({
  courseId,
  sessionId,
  label,
}: {
  courseId: number;
  /** Absent for goals that hang off an exercise only; those sessions can't be renamed here. */
  sessionId: number | undefined;
  label: string;
}) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<string | null>(null);
  const rename = useMutation({
    mutationFn: async (next: string) => {
      const { error } = await api.PATCH(
        "/api/courses/{courseId}/hierarchy-nodes/{nodeId}",
        { params: { path: { courseId, nodeId: sessionId! } }, body: { label: next } },
      );
      if (error) throw new Error("Could not rename the session.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      setDraft(null);
    },
  });
  const cancel = () => {
    rename.reset();
    setDraft(null);
  };

  if (draft == null) {
    return (
      <p className="mt-0.5 flex min-w-0 items-center gap-1 text-xs text-hestia-text-muted">
        <span className="truncate" title={label}>
          Session: <span className="text-hestia-text">{label}</span>
        </span>
        {sessionId != null && (
          <button
            type="button"
            aria-label={`Rename session ${label}`}
            title="Rename this session for all of its goals"
            onClick={() => {
              rename.reset();
              setDraft(label);
            }}
            className="flex h-5 w-5 shrink-0 items-center justify-center rounded text-hestia-text-muted transition hover:bg-hestia-primary-muted hover:text-hestia-text"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
              className="h-3.5 w-3.5"
            >
              <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
            </svg>
          </button>
        )}
      </p>
    );
  }
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        const trimmed = draft.trim();
        if (trimmed === "" || trimmed === label) cancel();
        else if (!rename.isPending) rename.mutate(trimmed);
      }}
      className="mt-1 flex flex-col gap-1"
    >
      <input
        value={draft}
        autoFocus
        disabled={rename.isPending}
        aria-label="Session name"
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          // Escape ends the rename, not the panel around it.
          if (e.key === "Escape") {
            e.preventDefault();
            e.stopPropagation();
            cancel();
          }
        }}
        className="w-full rounded-sm border-[1.5px] border-hestia-primary bg-hestia-bg px-2 py-1 text-xs text-hestia-text outline-none"
      />
      <p
        className={`text-xs leading-snug ${rename.isError ? "text-hestia-danger" : "text-hestia-text-muted"}`}
      >
        {rename.isError
          ? (rename.error as Error).message
          : rename.isPending
            ? "Saving…"
            : "Renames the session for every goal in it. Enter saves, Esc cancels."}
      </p>
    </form>
  );
}
