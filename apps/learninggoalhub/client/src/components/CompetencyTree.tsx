import {
  lazy,
  Suspense,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactElement,
  type CSSProperties,
  type ReactNode,
} from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, API_PREFIX } from "../api/client.ts";
import type { LearningGoal } from "../api/client.ts";
import CompetencyCreationField from "./CompetencyCreationField.tsx";
import TopicSearchDialog from "./TopicSearchDialog.tsx";
import { createTopic } from "../lib/createTopic.ts";
import AnchoredPopover from "./AnchoredPopover.tsx";
import Button from "./Button.tsx";
import ErrorBoundary from "./ErrorBoundary.tsx";
import FilterPopover from "./FilterPopover.tsx";
import CoverageBadge from "./CoverageBadge.tsx";
import TopicMap, { type MapCreation } from "./TopicMap.tsx";
import { RenameField, RowAction } from "./GoalInlineEditing.tsx";
// Lazily loaded so the heavy pdf.js bundle only ships once a source is opened, not on first paint.
const SourcePdfPane = lazy(() => import("./SourcePdfPane.tsx"));
import {
  BLOOM_DESC,
  COMPETENCY_ROLE_META,
  SOLO_DESC,
  buildCompetencyForest,
  coverageCounts,
  coverageOf,
  displayedGoalLabel,
  levelFlags,
  tierNoun,
  titleCase,
  type CompetencyNode,
  type CompetencyRole,
  type Coverage,
  type CoverageCounts,
} from "../lib/goals.ts";

/**
 * The competency tree as an Excel-like grid. Every goal attribute is a proper column with a funnel
 * filter (multi-select checkboxes). Rows keep lecture order. The grid browses the tree in one of two layouts, switched in its toolbar:
 *
 * - `table`: the Topic → Capability → Skill hierarchy lives in the first column with
 *   expand/collapse carets, and branches unfold as rows in place.
 * - `map`: the grid lists topics only, and clicking a topic row opens that topic's map inline
 *   beneath it (see `TopicMap`). The open row stays pinned while the map is read.
 *
 * Filter semantics: while a filter or search is active the grid becomes a list of matches. The
 * matching capabilities, skills and knowledge appear as rows in their tree position, and ancestors
 * of a match that don't match themselves are shown dimmed as context-only rows. Rows in that list
 * fold as table rows do, in both layouts; clearing the filters brings back the chosen layout.
 *
 * It renders as a CSS grid (not a <table>) so rows can animate in the map's language: opening a
 * table branch cascades its rows in with a light overshoot while the rows below glide down (FLIP),
 * collapsing a branch fades its rows out before the survivors slide up, and opening a map or
 * changing a filter lets the surviving rows glide to their new place.
 */

/** One goal flattened out of the forest, with the tree structure kept via parent ids. */
type Row = {
  id: number;
  parent: number | null;
  number: string;
  goal: LearningGoal;
  role: CompetencyRole;
  /** The session the goal's own source sits in; empty for synthesised and hand-added goals. */
  session: string;
};

type FilterKey = "role" | "kind" | "bloom" | "solo" | "document" | "session" | "coverage";
type LevelScale = "bloom" | "solo";
type GoalChanges = {
  text?: string;
  bloomLevel?: LearningGoal["bloomLevel"];
  soloLevel?: LearningGoal["soloLevel"];
};
type Layout = "table" | "diagram";
type CreationTier = 1 | 2 | 3 | 4;
type CreationState = {
  key: string;
  tier: CreationTier;
  parentGoalId: number | null;
  text: string;
  /** A topic created together with AI-written skills beneath it, instead of on its own. */
  generate?: boolean;
};

type AttributeKey = "role" | "coverage" | "kind" | "bloom" | "solo" | "source";
type ColumnKey = "text" | AttributeKey;
/**
 * The reader's column layout: the order of the attribute columns, which of them are hidden and the
 * widths they were dragged to. The learning-goal column always comes first and can't be hidden,
 * because the tree's carets live in it. Kept in the browser, so the layout survives reloads.
 */
type ColumnPrefs = {
  order: AttributeKey[];
  hidden: AttributeKey[];
  widths: Partial<Record<ColumnKey, number>>;
};
const COLUMN_PREFS_KEY = "learninggoalhub.competencyTable.columns";
const MIN_COLUMN_WIDTH = 64;
const MIN_GOAL_COLUMN_WIDTH = 160;

/** Maps a title-cased ladder term back to its API enum value ("Extended Abstract" → "EXTENDED_ABSTRACT"). */
const toEnum = (term: string) => term.toUpperCase().replace(/ /g, "_");

// Each taxonomy's ladder is the insertion order of its description map, the same order the goal
// modal's dot scales use.
const LEVEL_SCALES = {
  bloom: { label: "Bloom", desc: BLOOM_DESC },
  solo: { label: "SOLO", desc: SOLO_DESC },
} as const;
const BLOOM_ORDER = Object.keys(BLOOM_DESC).map(toEnum);

/** What a row's "+" adds beneath it. Knowledge and gaps take no children. */
const CHILD_APPEND: Partial<
  Record<
    CompetencyRole,
    { tier: 2 | 3 | 4; label: string; placeholder: string; color: string }
  >
> = {
  topic: {
    tier: 2,
    label: "Add skill",
    placeholder: "Describe a skill…",
    color: COMPETENCY_ROLE_META.capability.color,
  },
  capability: {
    tier: 3,
    label: "Add sub-skill",
    placeholder: "Describe a sub-skill…",
    color: COMPETENCY_ROLE_META.skill.color,
  },
  skill: {
    tier: 4,
    label: "Add knowledge",
    placeholder: "Describe what students should know…",
    color: COMPETENCY_ROLE_META.knowledge.color,
  },
};
const SOLO_ORDER = Object.keys(SOLO_DESC).map(toEnum);

// "AI_INFERRED" is a synthetic kind value derived from a goal's WIZARD_AI_SUBTREE provenance, so the
// Kind column and its filter surface AI-generated goals without a separate GoalKind enum on the server.
const NO_IDS: ReadonlySet<number> = new Set();
const AI_INFERRED_KIND = "AI_INFERRED";
const MANUAL_KIND = "MANUAL";
const KIND_ORDER = ["EXPLICIT", "IMPLICIT", AI_INFERRED_KIND, MANUAL_KIND];
// Coverage filter values for goals with a source: sourced only from lectures, only from exercises,
// or from both. Goals without a source, or whose documents have no kind, match none.
const LECTURE_ONLY = "LECTURE_ONLY";
const EXERCISE_ONLY = "EXERCISE_ONLY";
const BOTH = "BOTH";
const COVERAGE_ORDER = [LECTURE_ONLY, EXERCISE_ONLY, BOTH];
const COVERAGE_VALUE: Partial<Record<Coverage, string>> = {
  lecture: LECTURE_ONLY,
  exercise: EXERCISE_ONLY,
  both: BOTH,
};
const ROLE_ORDER: CompetencyRole[] = [
  "topic",
  "capability",
  "skill",
  "knowledge",
  "gap",
];

const prefersReducedMotion = () =>
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/**
 * Topics and capabilities are generated to group what extraction found, so explicit vs implicit
 * says nothing about them: every one is stored as IMPLICIT.
 */
const isGrouping = (role: CompetencyRole) => role === "topic" || role === "capability";

/** The filterable values of a row in one column. */
function valuesOf(row: Row, key: FilterKey): string[] {
  switch (key) {
    case "role":
      return [row.role];
    case "kind":
      if (row.goal.creationProvenance === "WIZARD_AI_SUBTREE")
        return [AI_INFERRED_KIND];
      if (row.goal.creationProvenance === "USER_CREATED") return [MANUAL_KIND];
      return [isGrouping(row.role) ? "" : (row.goal.kind ?? "")];
    case "bloom":
      return [row.goal.bloomLevel ?? ""];
    case "solo":
      return [row.goal.soloLevel ?? ""];
    case "document":
      return [documentOf(row)];
    // Only real sessions: an exercise's title is its file name, which the document filter already lists.
    case "session":
      return [row.goal.hierarchy?.session ?? ""];
    case "coverage": {
      const coverage = coverageOf(row.goal);
      return [(coverage && COVERAGE_VALUE[coverage]) ?? ""];
    }
  }
}

/** The name of the document a row's own source comes from; empty when it has no source. */
function documentOf(row: Row): string {
  const source = row.goal.sources?.[0];
  return source?.displayName || source?.filename || "";
}

/** Session is preferred; exercise titles preserve useful context for exercise-only goals. */
function sessionTitleOf(goal: LearningGoal): string {
  return goal.hierarchy?.session ?? goal.hierarchy?.exercise ?? "";
}

/**
 * The row that gathers a topic's goals in no skill. It is no goal: it has no number, levels or
 * actions, only a fold, and while folded it counts what it holds like a skill does.
 */
function LooseGroupRow({
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
            style={{ borderColor: COMPETENCY_ROLE_META.skill.color }}
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
            <span className="italic">Sub-skills without a skill</span>
            {!open && <ChildPreview role="capability" items={items} fullWording={false} />}
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
function ChildPreview({
  role,
  items,
  fullWording,
}: {
  role: CompetencyRole;
  items: Row[];
  fullWording: boolean;
}) {
  if (role !== "topic") {
    const counts = new Map<CompetencyRole, number>();
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
 * Flags a topic none of whose sub-skills comes from an exercise, the case a reader has to spot.
 * Every other topic leaves the cell empty.
 */
function TopicCoverage({ coverage }: { coverage: CoverageCounts }) {
  const known = coverage.total - coverage.unknown;
  if (known === 0 || coverage.practised > 0) return null;
  return (
    <Chip tone="warning" title={`None of its ${known} sub-skills comes from an exercise`}>
      <svg
        viewBox="0 0 20 20"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        className="h-3 w-3"
        style={{ color: "color-mix(in srgb, var(--hestia-warning) 70%, var(--hestia-text))" }}
      >
        <path d="M10 3.5l7 12.5H3z" />
        <path d="M10 8.5v3.5M10 14.2v.01" />
      </svg>
      No exercises
    </Chip>
  );
}

/** The tier's colour, faded for knowledge; the rail beside a row and the preview dots share it. */
function tierRailColor(role: CompetencyRole): string {
  const color = COMPETENCY_ROLE_META[role].color;
  return role === "knowledge" ? `color-mix(in srgb, ${color} 55%, transparent)` : color;
}

/** A preview dot in its tier's rail colour, so a collapsed branch previews in the colours it unfolds to. */
function TierDot({ role }: { role: CompetencyRole }) {
  return (
    <span
      aria-hidden="true"
      className="h-1.5 w-1.5 shrink-0 rounded-full"
      style={{ backgroundColor: tierRailColor(role) }}
    />
  );
}

/** Human label for a raw column value (role names, title-cased enums). */
function displayValue(key: FilterKey, value: string): string {
  if (key === "role")
    return COMPETENCY_ROLE_META[value as CompetencyRole].label;
  if (key === "session" || key === "document") return value || "—";
  if (value === LECTURE_ONLY) return "Lecture only";
  if (value === EXERCISE_ONLY) return "Exercise only";
  if (value === BOTH) return "Both";
  if (value === AI_INFERRED_KIND) return "AI-inferred";
  if (value === MANUAL_KIND) return "Manual";
  return value ? titleCase(value) : "—";
}

/** What each filter is called in the active-filter chips and in a multi-group popover. */
const FILTER_LABELS: Record<FilterKey, string> = {
  role: "Tier",
  kind: "Kind",
  bloom: "Bloom",
  solo: "SOLO",
  document: "Document",
  session: "Session",
  coverage: "Coverage",
};

const COLUMNS: {
  key: ColumnKey;
  label: string;
  /** Starting width in pixels; the learning-goal column also takes all the free space until resized. */
  width: number;
  /** The attributes the column's funnel filters on; Source offers both its document and session. */
  filterKeys?: FilterKey[];
}[] = [
  { key: "text", label: "Learning goal", width: 240 },
  { key: "role", label: "Tier", width: 80, filterKeys: ["role"] },
  { key: "coverage", label: "Coverage", width: 120, filterKeys: ["coverage"] },
  { key: "kind", label: "Kind", width: 84, filterKeys: ["kind"] },
  { key: "bloom", label: "Bloom", width: 132, filterKeys: ["bloom"] },
  { key: "solo", label: "SOLO", width: 156, filterKeys: ["solo"] },
  {
    key: "source",
    label: "Source",
    width: 168,
    filterKeys: ["document", "session"],
  },
];
const COLUMN_BY_KEY = new Map(COLUMNS.map((column) => [column.key, column]));
const ATTRIBUTE_KEYS = COLUMNS.slice(1).map((column) => column.key as AttributeKey);
const DEFAULT_COLUMN_PREFS: ColumnPrefs = { order: ATTRIBUTE_KEYS, hidden: [], widths: {} };

/** Reads the stored column layout, dropping keys that no longer exist and appending new columns. */
function loadColumnPrefs(): ColumnPrefs {
  try {
    const stored = JSON.parse(localStorage.getItem(COLUMN_PREFS_KEY) ?? "null") as Partial<ColumnPrefs> | null;
    if (!stored) return DEFAULT_COLUMN_PREFS;
    const known = (keys: unknown) =>
      (Array.isArray(keys) ? keys : []).filter((key): key is AttributeKey =>
        ATTRIBUTE_KEYS.includes(key),
      );
    const order = [...new Set(known(stored.order))];
    const widths: ColumnPrefs["widths"] = {};
    for (const column of COLUMNS) {
      const width = stored.widths?.[column.key];
      if (typeof width === "number" && Number.isFinite(width)) widths[column.key] = width;
    }
    return {
      order: [...order, ...ATTRIBUTE_KEYS.filter((key) => !order.includes(key))],
      hidden: known(stored.hidden),
      widths,
    };
  } catch {
    return DEFAULT_COLUMN_PREFS;
  }
}

/**
 * The grid template shared by the header and every row, so their columns always line up. The
 * learning-goal column fills the free space until it is resized; after that an empty trailing track
 * takes the free space instead, so a narrowed goal column really gets narrower.
 */
function gridTemplate(keys: ColumnKey[], widths: ColumnPrefs["widths"]): string {
  const goalWidth = widths.text;
  return [
    ...keys.map((key) =>
      key === "text"
        ? goalWidth != null
          ? `${goalWidth}px`
          : `minmax(${COLUMN_BY_KEY.get("text")!.width}px,1fr)`
        : `${widths[key] ?? COLUMN_BY_KEY.get(key)!.width}px`,
    ),
    ...(goalWidth != null ? ["minmax(0,1fr)"] : []),
  ].join(" ");
}

export default function CompetencyTree({
  courseId,
  goals,
  onUpdate,
  onDelete,
}: {
  courseId: number;
  goals: LearningGoal[];
  onUpdate: (
    goalId: number,
    changes: {
      text?: string;
      bloomLevel?: LearningGoal["bloomLevel"];
      soloLevel?: LearningGoal["soloLevel"];
    },
  ) => void;
  onDelete: (goal: LearningGoal) => void;
}) {
  const queryClient = useQueryClient();
  const [creation, setCreation] = useState<CreationState | null>(null);
  // The typed topic being looked up in the slides; the creation field stays open behind the dialog.
  const [finding, setFinding] = useState<string | null>(null);
  const createMutation = useMutation({
    mutationFn: async (vars: CreationState) => {
      if (vars.tier === 1) {
        return createTopic(courseId, vars.text, vars.generate === true);
      }
      const result = await api.POST(
        "/api/courses/{courseId}/learning-goals/{goalId}/children",
        {
          params: { path: { courseId, goalId: vars.parentGoalId! } },
          // The tier of a childless node can't be read off its position, so it is stated.
          body: { text: vars.text, role: vars.tier === 4 ? "KNOWLEDGE" : "SKILL" },
        },
      );
      if (!result.data) {
        throw new Error(
          result.response.status === 409
            ? "Knowledge nodes cannot have children."
            : "Could not add the goal.",
        );
      }
      return result.data;
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
    },
  });

  const beginCreation = (tier: CreationTier, parentGoalId: number | null) => {
    createMutation.reset();
    setCreation({
      key: `${tier}:${parentGoalId ?? "root"}`,
      tier,
      parentGoalId,
      text: "",
    });
  };
  const cancelCreation = () => {
    if (!createMutation.isPending) setCreation(null);
  };
  const updateCreationText = (text: string) => {
    if (createMutation.isError) createMutation.reset();
    setCreation((current) => (current ? { ...current, text } : current));
  };
  const submitCreation = (generate = false) => {
    if (!creation || creation.text.trim() === "") return;
    createMutation.mutate(
      { ...creation, text: creation.text.trim(), generate },
      { onSuccess: () => setCreation(null) },
    );
  };

  // Edits made in the grid show at once: they overlay the goals until the next fetch replaces the
  // list, which then carries them for real.
  const [pending, setPending] = useState<{
    base: LearningGoal[];
    changes: Map<number, GoalChanges>;
  }>(() => ({ base: goals, changes: new Map() }));
  const effectiveGoals = useMemo(() => {
    if (pending.base !== goals || pending.changes.size === 0) return goals;
    return goals.map((goal) => {
      const changes = pending.changes.get(goal.id!);
      if (!changes) return goal;
      // A new wording invalidates the short label derived from the old one.
      return {
        ...goal,
        ...changes,
        ...(changes.text !== undefined ? { shortLabel: undefined } : {}),
      };
    });
  }, [goals, pending]);
  const updateGoal = (goalId: number, changes: GoalChanges) => {
    setPending((prev) => {
      const next = new Map(prev.base === goals ? prev.changes : undefined);
      next.set(goalId, { ...next.get(goalId), ...changes });
      return { base: goals, changes: next };
    });
    onUpdate(goalId, changes);
  };

  const forest = useMemo(
    () => buildCompetencyForest(effectiveGoals),
    [effectiveGoals],
  );
  const rows = useMemo(() => flattenForest(forest), [forest]);
  const flags = useMemo(() => levelFlags(forest), [forest]);
  const topicCoverage = useMemo(
    () => new Map(forest.map((node) => [node.goal.id, coverageCounts(node)])),
    [forest],
  );
  const childrenOf = useMemo(() => {
    const map = new Map<number | null, Row[]>();
    for (const row of rows) {
      const list = map.get(row.parent) ?? [];
      list.push(row);
      map.set(row.parent, list);
    }
    return map;
  }, [rows]);
  const byId = useMemo(() => new Map(rows.map((r) => [r.id, r])), [rows]);

  // How many children a row shows in the table layout, which decides whether it gets a caret.
  const visibleChildCount = useMemo(() => {
    const map = new Map<number, number>();
    for (const row of rows) {
      if (row.parent == null) continue;
      map.set(row.parent, (map.get(row.parent) ?? 0) + 1);
    }
    return map;
  }, [rows]);

  const [layout, setLayout] = useState<Layout>("table");
  // Everything starts collapsed in both layouts: the topics alone are the overview, and opening
  // one is the reader's first deliberate step rather than a state they arrive in. Each layout
  // keeps its own state, so switching back returns to where the reader left it.
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  // One map is open at a time.
  const [openTopicId, setOpenTopicId] = useState<number | null>(null);
  const [search, setSearch] = useState("");
  const [filters, setFilters] = useState<Record<FilterKey, Set<string>>>({
    role: new Set(),
    kind: new Set(),
    bloom: new Set(),
    solo: new Set(),
    document: new Set(),
    session: new Set(),
    coverage: new Set(),
  });
  // Rows show short labels by default; the toolbar switch swaps in every goal's full wording.
  const [fullWording, setFullWording] = useState(false);
  const [columnPrefs, setColumnPrefs] = useState<ColumnPrefs>(loadColumnPrefs);
  useEffect(() => {
    try {
      localStorage.setItem(COLUMN_PREFS_KEY, JSON.stringify(columnPrefs));
    } catch {
      // Without storage the layout simply lasts until the page is left.
    }
  }, [columnPrefs]);
  const [displayMenuOpen, setDisplayMenuOpen] = useState(false);
  const visibleAttributes = columnPrefs.order.filter((key) => !columnPrefs.hidden.includes(key));
  const visibleColumns = (["text", ...visibleAttributes] as ColumnKey[]).map(
    (key) => COLUMN_BY_KEY.get(key)!,
  );
  const gridCols = gridTemplate(
    visibleColumns.map((column) => column.key),
    columnPrefs.widths,
  );
  const tableMinWidth = visibleColumns.reduce(
    (sum, column) => sum + (columnPrefs.widths[column.key] ?? column.width),
    0,
  );
  // The header being dragged to a new place, and the edge of the header it would land on.
  const [draggedColumn, setDraggedColumn] = useState<AttributeKey | null>(null);
  const [dropTarget, setDropTarget] = useState<{
    key: ColumnKey;
    side: "before" | "after";
  } | null>(null);
  const endColumnDrag = () => {
    setDraggedColumn(null);
    setDropTarget(null);
  };
  const moveColumn = (key: AttributeKey, target: ColumnKey, side: "before" | "after") =>
    setColumnPrefs((prev) => {
      const order = prev.order.filter((other) => other !== key);
      // Nothing moves in front of the learning-goal column.
      const index = target === "text" ? 0 : order.indexOf(target) + (side === "after" ? 1 : 0);
      order.splice(index, 0, key);
      return { ...prev, order };
    });
  const resizeColumn = (key: ColumnKey, width: number | null) =>
    setColumnPrefs((prev) => {
      const widths = { ...prev.widths };
      if (width == null) delete widths[key];
      else widths[key] = width;
      return { ...prev, widths };
    });
  const toggleColumnHidden = (key: AttributeKey) =>
    setColumnPrefs((prev) => ({
      ...prev,
      hidden: prev.hidden.includes(key)
        ? prev.hidden.filter((other) => other !== key)
        : [...prev.hidden, key],
    }));
  // The column (by key) whose filter popover is open.
  const [openFilter, setOpenFilter] = useState<string | null>(null);

  // At most one level menu and one inline rename are open at a time across the grid.
  const [openLevel, setOpenLevel] = useState<{ id: number; scale: LevelScale } | null>(null);
  const toggleLevel = (row: Row, scale: LevelScale) =>
    setOpenLevel((prev) =>
      prev?.id === row.id && prev.scale === scale ? null : { id: row.id, scale },
    );
  const [editingId, setEditingId] = useState<number | null>(null);

  // The goal whose source is open in the PDF panel beside the grid. Kept as an id so the panel
  // follows refetches and closes itself once the goal is gone.
  const [sourceGoalId, setSourceGoalId] = useState<number | null>(null);
  const sourceRow = sourceGoalId != null ? byId.get(sourceGoalId) : undefined;
  const openSource = sourceRow?.goal.sources?.[0];
  const sourcePaneRef = useRef<HTMLDivElement>(null);
  const sourceTriggerRef = useRef<HTMLElement | null>(null);
  const showSource = (row: Row, trigger: HTMLElement) => {
    sourceTriggerRef.current = trigger;
    setSourceGoalId(row.id);
  };
  // Focus moves into the panel on open and back to the cell that opened it on close, so a keyboard
  // user is never left on a detached element.
  useEffect(() => {
    if (sourceGoalId != null) {
      sourcePaneRef.current?.focus({ preventScroll: true });
    } else if (sourceTriggerRef.current?.isConnected) {
      sourceTriggerRef.current.focus({ preventScroll: true });
      sourceTriggerRef.current = null;
    }
  }, [sourceGoalId]);
  // Escape closes the panel, unless a popover or rename above it owns the key.
  useEffect(() => {
    if (
      sourceGoalId == null ||
      openFilter != null ||
      openLevel != null ||
      editingId != null ||
      creation != null ||
      displayMenuOpen
    )
      return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        setSourceGoalId(null);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [sourceGoalId, openFilter, openLevel, editingId, creation, displayMenuOpen]);

  const filtering =
    search.trim() !== "" || Object.values(filters).some((s) => s.size > 0);

  // Rows surviving the filters, plus their non-matching ancestors as dimmed context.
  const { matchIds, contextIds } = useMemo(() => {
    if (!filtering)
      return {
        matchIds: null as Set<number> | null,
        contextIds: new Set<number>(),
      };
    const needle = search.trim().toLowerCase();
    const matchesRow = (row: Row): boolean => {
      if (
        needle &&
        ![row.goal.shortLabel, row.goal.text].some((value) =>
          (value ?? "").toLowerCase().includes(needle),
        )
      )
        return false;
      for (const key of Object.keys(filters) as FilterKey[]) {
        const set = filters[key];
        if (set.size > 0 && !valuesOf(row, key).some((v) => set.has(v)))
          return false;
      }
      return true;
    };
    const matches = new Set(rows.filter(matchesRow).map((r) => r.id));
    const context = new Set<number>();
    for (const id of matches) {
      let parent = byId.get(id)?.parent ?? null;
      while (parent != null && !matches.has(parent)) {
        context.add(parent);
        parent = byId.get(parent)?.parent ?? null;
      }
    }
    return {
      matchIds: matches,
      contextIds: context,
    };
  }, [rows, byId, search, filters, filtering]);

  // A filtered list opens every branch down to its matches, and rows collapsed in it stay collapsed
  // only while the same filter holds: changing the filter or search starts fully open again. This
  // is kept apart from `expanded`, so clearing the filter returns to the tree as it was left.
  const filterSignature = JSON.stringify([
    search.trim(),
    Object.entries(filters).map(([key, values]) => [key, [...values].sort()]),
  ]);
  const [filterFold, setFilterFold] = useState<{ signature: string; collapsed: Set<number> }>({
    signature: "",
    collapsed: new Set(),
  });
  const filterCollapsed =
    filterFold.signature === filterSignature ? filterFold.collapsed : NO_IDS;
  const setFilterCollapsed = (update: (collapsed: Set<number>) => Set<number>) =>
    setFilterFold((prev) => ({
      signature: filterSignature,
      collapsed: update(prev.signature === filterSignature ? prev.collapsed : new Set()),
    }));
  // How many children a row shows in the filtered list, which decides whether it gets a caret.
  const filteredChildCount = useMemo(() => {
    const map = new Map<number, number>();
    if (!matchIds) return map;
    for (const row of rows) {
      if (row.parent == null || !(matchIds.has(row.id) || contextIds.has(row.id))) continue;
      map.set(row.parent, (map.get(row.parent) ?? 0) + 1);
    }
    return map;
  }, [rows, matchIds, contextIds]);
  const isOpen = (id: number) => (filtering ? !filterCollapsed.has(id) : expanded.has(id));
  // The goals under a topic that sit in no skill, gathered under one "Sub-skills without a skill"
  // row. That row is no goal; it folds under the negated topic id, which no goal id collides with.
  const looseOf = (topicId: number) =>
    (childrenOf.get(topicId) ?? []).filter((child) => child.role !== "capability");
  const looseGroupId = (topicId: number) => -topicId;
  const isVisible = (row: Row) =>
    !filtering || matchIds!.has(row.id) || contextIds.has(row.id);
  const childCountOf = (id: number) =>
    (filtering ? filteredChildCount : visibleChildCount).get(id) ?? 0;

  // Only offer filter values that actually occur, so no filter can promise a value that has no row
  // to land on.
  const filterOptions = useMemo(() => {
    const present = (key: FilterKey) =>
      new Set(rows.flatMap((r) => valuesOf(r, key)).filter((v) => v !== ""));
    const ordered = (order: string[], values: Set<string>) =>
      order.filter((v) => values.has(v));
    return {
      role: ordered(ROLE_ORDER, present("role")),
      kind: ordered(KIND_ORDER, present("kind")),
      bloom: ordered(BLOOM_ORDER, present("bloom")),
      solo: ordered(SOLO_ORDER, present("solo")),
      document: [...present("document")].sort((a, b) => a.localeCompare(b)),
      session: [...present("session")].sort((a, b) => a.localeCompare(b)),
      coverage: ordered(COVERAGE_ORDER, present("coverage")),
    };
  }, [rows]);

  // ── Row animation (map language). A single FLIP pass after each render: surviving rows
  // (measured last render) glide to their new position, and rows a table branch just revealed
  // cascade in with a light overshoot. Collapsing is handled imperatively below so its rows can
  // fade out first. Rows are tagged `data-row-id` rather than `data-goal-id` so the boxes inside an
  // open map, which run their own FLIP, are left alone. ──
  const containerRef = useRef<HTMLDivElement>(null);
  const scrollerRef = useRef<HTMLDivElement>(null);
  const headerRef = useRef<HTMLDivElement>(null);
  // Positions are layout-relative (`offsetTop`), not viewport-relative: the grid sits in its own
  // scroll container, so a rect measured before a scroll differs by the scrolled distance and
  // would make every surviving row glide in from nowhere.
  const prevTops = useRef<Map<number, number>>(new Map());
  const firstLayout = useRef(true);
  // Which newly-revealed rows may cascade in on the next layout: the descendants of a branch just
  // opened, or "all" for expand-all. Empty for every other change (filter / search / map),
  // so those rows simply appear while the survivors glide. Consumed after each layout pass.
  const enterIntent = useRef<Set<number> | "all">(new Set());
  // Set when a map was just opened, so the next layout scrolls its row up under the header.
  const revealOpened = useRef(false);

  useLayoutEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const reduce = prefersReducedMotion();
    const prev = prevTops.current;
    const intent = enterIntent.current;
    const next = new Map<number, number>();
    let enterIndex = 0;
    container.querySelectorAll<HTMLElement>("[data-row-id]").forEach((el) => {
      const id = Number(el.dataset.rowId);
      const to = el.offsetTop;
      next.set(id, to);
      if (reduce || firstLayout.current) return;
      // Clear any leftover entrance state from an earlier cascade before deciding afresh.
      el.classList.remove("tree-row-in");
      el.style.animationDelay = "";
      const from = prev.get(id);
      if (from != null) {
        if (Math.abs(from - to) > 1)
          el.animate(
            [{ transform: `translateY(${from - to}px)` }, { transform: "none" }],
            { duration: 320, easing: "cubic-bezier(0.2, 0, 0.2, 1)" },
          );
      } else if (intent === "all" || intent.has(id)) {
        el.style.animationDelay = `${enterIndex++ * 38}ms`;
        el.classList.add("tree-row-in");
      }
    });
    prevTops.current = next;
    enterIntent.current = new Set();
    firstLayout.current = false;

    const scroller = scrollerRef.current;
    const opened =
      openTopicId != null
        ? container.querySelector<HTMLElement>(`[data-row-id="${openTopicId}"]`)
        : null;
    if (revealOpened.current && scroller && opened) {
      scroller.scrollTo({
        top: opened.offsetTop - headerHeight,
        behavior: reduce ? "auto" : "smooth",
      });
    }
    revealOpened.current = false;
  });

  // The open topic row sticks right under the column header, so it needs the header's height.
  const [headerHeight, setHeaderHeight] = useState(0);
  const hasTree = forest.length > 0;
  useLayoutEffect(() => {
    const header = headerRef.current;
    if (!header) return;
    const measure = () => setHeaderHeight(header.offsetHeight);
    const observer = new ResizeObserver(measure);
    observer.observe(header);
    measure();
    return () => observer.disconnect();
  }, [hasTree]);

  const toggleMap = (id: number) => {
    if (openTopicId !== id) revealOpened.current = true;
    setOpenTopicId((prev) => (prev === id ? null : id));
  };

  const descendantIds = (id: number): Set<number> => {
    const out = new Set<number>();
    const walk = (pid: number) => {
      // A group's children are its topic's loose goals; a topic's descendants include its group.
      const children = pid < 0 ? looseOf(-pid) : (childrenOf.get(pid) ?? []);
      if (pid > 0 && byId.get(pid)?.role === "topic" && looseOf(pid).length > 0)
        out.add(looseGroupId(pid));
      for (const child of children) {
        out.add(child.id);
        walk(child.id);
      }
    };
    walk(id);
    return out;
  };

  const expand = (id: number) => {
    enterIntent.current = descendantIds(id);
    if (filtering) {
      setFilterCollapsed((prev) => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
    } else {
      setExpanded((prev) => new Set(prev).add(id));
    }
  };

  // Collapse: fade the currently-visible descendants out, then drop them from the tree (the FLIP
  // pass then slides the survivors up). Falls back to an instant collapse when motion is reduced.
  const collapse = (id: number) => {
    const remove = () =>
      filtering
        ? setFilterCollapsed((prev) => new Set(prev).add(id))
        : setExpanded((prev) => {
            const next = new Set(prev);
            next.delete(id);
            return next;
          });
    const container = containerRef.current;
    if (!container || prefersReducedMotion()) {
      remove();
      return;
    }
    const kill = descendantIds(id);
    const leaving = [
      ...container.querySelectorAll<HTMLElement>("[data-row-id]"),
    ].filter((el) => kill.has(Number(el.dataset.rowId)));
    if (leaving.length === 0) {
      remove();
      return;
    }
    leaving.forEach((el, i) => {
      el.style.animationDelay = `${i * 12}ms`;
      el.classList.add("tree-row-out");
    });
    window.setTimeout(remove, 150);
  };

  const onToggle = (id: number) => (isOpen(id) ? collapse(id) : expand(id));

  const parentIds = useMemo(
    () =>
      rows.flatMap((r) => {
        const ids =
          ((filtering ? filteredChildCount : visibleChildCount).get(r.id) ?? 0) > 0 ? [r.id] : [];
        if (r.role === "topic" && looseOf(r.id).some(isVisible)) ids.push(looseGroupId(r.id));
        return ids;
      }),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- looseOf and isVisible read the same inputs
    [rows, filtering, filteredChildCount, visibleChildCount, childrenOf, matchIds, contextIds],
  );
  const allOpen = parentIds.length > 0 && parentIds.every(isOpen);

  const mapCreation: MapCreation = {
    activeKey: creation?.key ?? null,
    value: creation?.text ?? "",
    pending: createMutation.isPending,
    error: createMutation.isError
      ? (createMutation.error as Error).message
      : undefined,
    begin: beginCreation,
    change: updateCreationText,
    submit: submitCreation,
    cancel: cancelCreation,
  };

  const toggleFilterValue = (key: FilterKey, value: string) =>
    setFilters((prev) => {
      const next = new Set(prev[key]);
      if (next.has(value)) next.delete(value);
      else next.add(value);
      return { ...prev, [key]: next };
    });

  const clearFilter = (key: FilterKey) =>
    setFilters((prev) => ({ ...prev, [key]: new Set() }));

  const clearAll = () => {
    setSearch("");
    setFilters({
      role: new Set(),
      kind: new Set(),
      bloom: new Set(),
      solo: new Set(),
      document: new Set(),
      session: new Set(),
      coverage: new Set(),
    });
  };

  if (!hasTree) {
    // Topics are named from lecture outcomes only, so a course whose sourced goals all come from
    // exercises extracts fine and still gets no tree; say so instead of the generic message.
    const coverages = goals.map(coverageOf).filter((coverage) => coverage != null);
    const exercisesOnly = coverages.length > 0 && coverages.every((coverage) => coverage === "exercise");
    return (
      <p className="mx-auto w-full max-w-5xl rounded-xl border border-dashed border-hestia-border p-8 text-center text-sm text-hestia-text-muted">
        {exercisesOnly
          ? "No competency tree was created: this course has only exercises. Topics are named from lecture material, and none was uploaded."
          : "No competency tree was created during extraction for this course."}
      </p>
    );
  }

  // A collapsed topic, skill or sub-skill previews what it holds, so a closed branch still says what
  // is inside.
  // A filtered list previews only the children it would show.
  const previewOf = (row: Row, mapOpen: boolean): Row[] | undefined => {
    if (row.role !== "topic" && row.role !== "capability" && row.role !== "skill") return undefined;
    const open = layout === "diagram" && !filtering ? mapOpen : isOpen(row.id);
    if (open) return undefined;
    const children = (childrenOf.get(row.id) ?? []).filter(
      (child) => !filtering || matchIds!.has(child.id) || contextIds.has(child.id),
    );
    return children.length > 0 ? children : undefined;
  };

  // Depth-first walk producing the visible rows. Browsing follows the layout — the table unfolds
  // expanded rows in place, the map layout lists topics with the open one's map beneath its row —
  // while a filter or search walks down to every match in either layout.
  const bodyRows: ReactElement[] = [];
  let rowIndex = 0;
  const walk = (
    siblings: Row[],
    depth: number,
    parentGoalId: number | null,
    parentRole: CompetencyRole | null,
    /**
     * Whether this group's knob lands in the stack of knobs at the very bottom of the grid. Their
     * containers are zero-height, so every trailing knob shares the same row and a tooltip opening
     * downward would be clipped by the scroll container — the whole stack has to open upward.
     */
    trailing: boolean,
  ) => {
    const emitRow = (row: Row, last: boolean) => {
    const isMatch = !filtering || matchIds!.has(row.id);
    const isContext = filtering && contextIds.has(row.id);
    if (filtering && !isMatch && !isContext) return;
    const mapOpen = layout === "diagram" && !filtering && openTopicId === row.id;
    // A goal hanging directly under a topic sits in the "Sub-skills without a skill" group, one
    // step further in, level with the sub-skills under a skill. Its knowledge follows it in.
    const rowDepth = row.role !== "capability" && parentRole === "topic" ? depth + 1 : depth;
    // The "+" lives in the table only: the map adds in the map, and a filtered list has no place
    // for the new row to appear.
    const childAppend = CHILD_APPEND[row.role];
    const canAddChild =
      layout === "table" &&
      !filtering &&
      childAppend != null;
    bodyRows.push(
      <GridRow
        key={row.id}
        row={row}
        depth={rowDepth}
        zebra={rowIndex++ % 2 === 1}
        context={isContext}
        filtering={filtering}
        layout={layout}
        open={isOpen(row.id)}
        childCount={childCountOf(row.id)}
        mapOpen={mapOpen}
        gridCols={gridCols}
        columns={visibleAttributes}
        stickyTop={headerHeight}
        onToggle={onToggle}
        onToggleMap={toggleMap}
        sourceOpen={sourceGoalId === row.id}
        onOpenSource={showSource}
        openLevel={openLevel?.id === row.id ? openLevel.scale : null}
        onToggleLevel={toggleLevel}
        onCloseLevel={() => setOpenLevel(null)}
        fullWording={fullWording}
        editing={editingId === row.id}
        onStartEdit={(target) => setEditingId(target.id)}
        onEndEdit={() => setEditingId(null)}
        onUpdate={updateGoal}
        onDelete={onDelete}
        levelFlag={flags.get(row.id)}
        coverage={row.role === "topic" ? topicCoverage.get(row.id) : undefined}
        preview={previewOf(row, mapOpen)}
        addChildLabel={canAddChild ? childAppend.label : undefined}
        onAddChild={
          canAddChild
            ? () => {
                beginCreation(childAppend.tier, row.id);
                // The field opens beneath the row's children, so they have to be showing.
                if (!expanded.has(row.id)) expand(row.id);
              }
            : undefined
        }
      />,
    );
    const topicNode = mapOpen
      ? forest.find((node) => node.goal.id === row.id)
      : undefined;
    if (topicNode) {
      bodyRows.push(
        <div
          key={`map-${row.id}`}
          role="row"
          className="border-b border-hestia-border bg-hestia-bg shadow-[inset_0_8px_10px_-10px_rgba(0,0,0,0.25)]"
        >
          <div role="gridcell" aria-colspan={visibleColumns.length}>
            <TopicMap
              topic={topicNode}
              sequence={row.number}
              editingId={editingId}
              onStartEdit={(goal) => setEditingId(goal.id!)}
              onEndEdit={(goal, text) => {
                if (text != null) updateGoal(goal.id!, { text });
                setEditingId(null);
              }}
              onDelete={onDelete}
              onClose={() => setOpenTopicId(null)}
              creation={mapCreation}
              fullWording={fullWording}
              attributesOf={(node) => {
                const nodeRow = byId.get(node.goal.id!);
                return nodeRow ? (
                  <DiagramAttributes
                    row={nodeRow}
                    columns={visibleAttributes}
                    levelFlag={flags.get(nodeRow.id)}
                    openLevel={openLevel?.id === nodeRow.id ? openLevel.scale : null}
                    onToggleLevel={toggleLevel}
                    onCloseLevel={() => setOpenLevel(null)}
                    onUpdate={updateGoal}
                    sourceOpen={sourceGoalId === nodeRow.id}
                    onOpenSource={showSource}
                  />
                ) : null;
              }}
              suspendEscape={
                openFilter != null ||
                sourceGoalId != null ||
                openLevel != null ||
                editingId != null
              }
            />
          </div>
        </div>,
      );
    }
    // In the table, a childless topic is still walked into so its "Add skill" knob has somewhere
    // to live.
    if (
      (filtering && !filterCollapsed.has(row.id)) ||
      (!filtering &&
        layout === "table" &&
        (expanded.has(row.id) ||
          ((visibleChildCount.get(row.id) ?? 0) === 0 && row.role === "topic")))
    )
      walk(
        childrenOf.get(row.id) ?? [],
        rowDepth + 1,
        row.id,
        row.role,
        last,
      );
    };
    const emitKnob = (last: boolean) => {
// "Add topic" always closes the grid and "Add skill" closes each topic; the
      // deeper tiers have no resting knob and only appear once a row's "+" opened them. In the map
      // layout, skills and sub-skills are added in the map itself.
      if (!filtering) {
        const append =
          depth === 0
            ? {
                tier: 1 as const,
                label: "Add topic",
                placeholder: "Describe a topic…",
                color: "var(--hestia-primary)",
              }
            : layout === "table" && parentRole != null
              ? CHILD_APPEND[parentRole]
              : undefined;
        const appendKey = append ? `${append.tier}:${parentGoalId ?? "root"}` : null;
        const appendActive = appendKey != null && creation?.key === appendKey;
        const resting =
          depth === 0 || parentRole === "topic";
        if (append && (appendActive || resting)) {
          bodyRows.push(
            <AppendKnob
              key={`append-${appendKey}`}
              depth={depth}
              label={append.label}
              placeholder={append.placeholder}
              color={append.color}
              last={last}
              active={appendActive}
              value={creation?.text ?? ""}
              pending={createMutation.isPending}
              error={
                appendActive && createMutation.isError
                  ? (createMutation.error as Error).message
                  : undefined
              }
              onStart={() => beginCreation(append.tier, parentGoalId)}
              onChange={updateCreationText}
              onSubmit={() => submitCreation()}
              onCancel={cancelCreation}
              // Only a topic can be generated; the tiers below it are added by hand.
              onGenerate={append.tier === 1 ? () => submitCreation(true) : undefined}
              generating={createMutation.variables?.generate === true}
              onFind={
                append.tier === 1
                  ? () => {
                      const text = creation?.text.trim() ?? "";
                      if (text !== "") setFinding(text);
                    }
                  : undefined
              }
            />,
          );
        }
      }
    };
    if (parentRole === "topic" && parentGoalId != null) {
      // Skills first; the goals in no skill follow under one foldable group row, after the knob that
      // adds a skill, so they never read as part of the skill above them.
      const skills = siblings.filter((row) => row.role === "capability");
      const loose = siblings.filter((row) => row.role !== "capability" && isVisible(row));
      skills.forEach((row, i) => emitRow(row, trailing && loose.length === 0 && i === skills.length - 1));
      emitKnob(trailing && loose.length === 0);
      if (loose.length > 0) {
        const groupId = looseGroupId(parentGoalId);
        const groupOpen = isOpen(groupId);
        bodyRows.push(
          <LooseGroupRow
            key={`loose-${parentGoalId}`}
            id={groupId}
            depth={depth}
            zebra={rowIndex++ % 2 === 1}
            open={groupOpen}
            // Never a match itself, so a filtered list dims it like any other ancestor.
            context={filtering}
            items={loose}
            gridCols={gridCols}
            columns={visibleAttributes}
            onToggle={onToggle}
          />,
        );
        if (groupOpen) loose.forEach((row, i) => emitRow(row, trailing && i === loose.length - 1));
      }
      return;
    }
    siblings.forEach((row, i) => emitRow(row, trailing && i === siblings.length - 1));
    emitKnob(trailing);
  };
  walk(childrenOf.get(null) ?? [], 0, null, null, true);


  const activeChips: { label: string; value: string; onRemove: () => void }[] =
    [];
  if (search.trim())
    activeChips.push({
      label: "Search",
      value: `“${search.trim()}”`,
      onRemove: () => setSearch(""),
    });
  for (const column of COLUMNS) {
    for (const key of column.filterKeys ?? []) {
      for (const value of filters[key]) {
        activeChips.push({
          label: FILTER_LABELS[key],
          value: displayValue(key, value),
          onRemove: () => toggleFilterValue(key, value),
        });
      }
    }
  }

  return (
    // The grid keeps the page's reading width, and widens only to make room for the PDF panel.
    <div
      className={`mx-auto flex w-full flex-col gap-3 ${openSource ? "" : "max-w-5xl"}`}
    >
      <div className="flex flex-wrap items-center gap-3">
        <LayoutSwitch layout={layout} onChange={setLayout} />
        <label className="relative flex min-w-48 flex-1 items-center">
          <svg
            viewBox="0 0 20 20"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="pointer-events-none absolute left-3 h-4 w-4 text-hestia-text-muted"
          >
            <circle cx="9" cy="9" r="6" />
            <path d="M14 14l4 4" />
          </svg>
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search goals…"
            className="h-9 w-full rounded-md border border-hestia-border bg-hestia-surface pl-9 pr-3 text-sm text-hestia-text transition placeholder:text-hestia-text-muted focus:border-hestia-primary focus:shadow-[0_0_0_3px_var(--hestia-primary-muted)] focus:outline-none"
          />
        </label>
        {(layout === "table" || filtering) && (
          <Button
            variant="neutral"
            className="h-9"
            onClick={() => {
              if (!allOpen) enterIntent.current = "all";
              if (filtering) {
                setFilterCollapsed(() => (allOpen ? new Set(parentIds) : new Set()));
              } else {
                setExpanded(allOpen ? new Set() : new Set(parentIds));
              }
            }}
          >
            <FoldIcon collapse={allOpen} />
            {allOpen ? "Collapse all" : "Expand all"}
          </Button>
        )}
        <DisplayMenu
          fullWording={fullWording}
          onChangeWording={setFullWording}
          prefs={columnPrefs}
          open={displayMenuOpen}
          onToggleOpen={() => setDisplayMenuOpen((prev) => !prev)}
          onClose={() => setDisplayMenuOpen(false)}
          onToggleHidden={toggleColumnHidden}
          onReset={() => setColumnPrefs(DEFAULT_COLUMN_PREFS)}
        />
      </div>

      {activeChips.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5">
          {activeChips.map((chip, i) => (
            <span
              key={i}
              className="inline-flex items-center gap-1.5 rounded-full border border-[color-mix(in_srgb,var(--hestia-primary)_35%,transparent)] bg-hestia-primary-muted py-0.5 pl-2.5 pr-1.5 text-xs"
            >
              <span>
                <b className="font-semibold">{chip.label}:</b> {chip.value}
              </span>
              <button
                type="button"
                onClick={chip.onRemove}
                aria-label={`Remove filter ${chip.label} ${chip.value}`}
                className="flex rounded-full text-hestia-text-muted transition hover:text-hestia-danger"
              >
                <CrossIcon />
              </button>
            </span>
          ))}
          {activeChips.length > 1 && (
            <button
              type="button"
              onClick={clearAll}
              className="text-xs text-hestia-text-muted underline transition hover:text-hestia-text"
            >
              Clear all
            </button>
          )}
        </div>
      )}

      <div className="flex flex-col gap-3 lg:flex-row lg:items-start">
      <div className="min-w-0 flex-1 overflow-hidden rounded-xl border border-hestia-border bg-hestia-surface shadow-sm">
        {/* pb-4 keeps the trailing append knob inside the scroll area instead of under its edge. */}
        <div ref={scrollerRef} className="relative max-h-[72vh] overflow-auto pb-4">
          <div
            role="table"
            aria-label="Competency tree"
            style={{ minWidth: tableMinWidth }}
          >
            <div
              ref={headerRef}
              role="row"
              className="sticky top-0 z-10 grid rounded-t-xl border-b border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_4%,var(--hestia-surface))]"
              style={{ gridTemplateColumns: gridCols }}
            >
              {visibleColumns.map((column) => (
                <HeaderCell
                  key={column.key}
                  column={column}
                  dragged={draggedColumn === column.key}
                  dropSide={dropTarget?.key === column.key ? dropTarget.side : null}
                  onDragStart={
                    column.key === "text"
                      ? undefined
                      : () => setDraggedColumn(column.key as AttributeKey)
                  }
                  onDragOver={
                    draggedColumn == null
                      ? undefined
                      : (side) => {
                          const target = {
                            key: column.key,
                            // Nothing lands in front of the learning-goal column.
                            side: column.key === "text" ? ("after" as const) : side,
                          };
                          if (dropTarget?.key !== target.key || dropTarget.side !== target.side)
                            setDropTarget(target);
                        }
                  }
                  onDrop={() => {
                    if (draggedColumn != null && dropTarget != null)
                      moveColumn(draggedColumn, dropTarget.key, dropTarget.side);
                    endColumnDrag();
                  }}
                  onDragEnd={endColumnDrag}
                  minWidth={column.key === "text" ? MIN_GOAL_COLUMN_WIDTH : MIN_COLUMN_WIDTH}
                  onResize={(width) => resizeColumn(column.key, width)}
                  filterActive={(column.filterKeys ?? []).some(
                    (key) => filters[key].size > 0,
                  )}
                  popoverOpen={openFilter === column.key}
                  onTogglePopover={() =>
                    setOpenFilter((prev) =>
                      prev === column.key ? null : column.key,
                    )
                  }
                  popover={
                    column.filterKeys && openFilter === column.key ? (
                      <FilterPopover
                        groups={column.filterKeys.map((key) => ({
                          key,
                          label: FILTER_LABELS[key],
                          options: filterOptions[key],
                          selected: filters[key],
                          display: (v) => displayValue(key, v),
                          onToggle: (v) => toggleFilterValue(key, v),
                        }))}
                        onClear={() => {
                          column.filterKeys!.forEach(clearFilter);
                          setOpenFilter(null);
                        }}
                        onClose={() => setOpenFilter(null)}
                      />
                    ) : null
                  }
                />
              ))}
            </div>
            <div role="rowgroup" ref={containerRef}>
              {bodyRows.length > 0 ? (
                bodyRows
              ) : (
                <div className="p-8 text-center text-sm text-hestia-text-muted">
                  No goals match the current filters.
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
      {openSource && (
        <div
          ref={sourcePaneRef}
          tabIndex={-1}
          className="w-full outline-none lg:sticky lg:top-4 lg:w-auto lg:shrink-0"
        >
          <ErrorBoundary
            resetKey={openSource.documentId}
            fallback={
              <div className="flex min-h-[32rem] w-full flex-col items-center justify-center gap-2 rounded-lg border border-hestia-border bg-hestia-surface text-center text-xs text-hestia-text-muted lg:w-[min(44vw,42rem)]">
                <p>Could not load the PDF preview.</p>
                {openSource.contentAvailable && (
                  <a
                    href={`${API_PREFIX}/api/courses/${courseId}/documents/${openSource.documentId}/content${openSource.page ? `#page=${openSource.page}` : ""}`}
                    target="_blank"
                    rel="noreferrer"
                    className="font-medium text-hestia-primary underline underline-offset-2"
                  >
                    Open PDF in a new tab
                  </a>
                )}
              </div>
            }
          >
            <Suspense
              fallback={
                <div className="flex min-h-[32rem] w-full items-center justify-center rounded-lg border border-hestia-border bg-hestia-surface text-xs text-hestia-text-muted lg:w-[min(44vw,42rem)]">
                  Loading preview…
                </div>
              }
            >
              <SourcePdfPane
                courseId={courseId}
                source={openSource}
                onClose={() => setSourceGoalId(null)}
                headerExtra={
                  sourceRow?.session ? (
                    <SessionName
                      key={sourceRow.goal.hierarchy?.sessionId ?? sourceRow.session}
                      courseId={courseId}
                      sessionId={sourceRow.goal.hierarchy?.sessionId}
                      label={sourceRow.session}
                    />
                  ) : undefined
                }
              />
            </Suspense>
          </ErrorBoundary>
        </div>
      )}
      </div>
      {finding != null && (
        <TopicSearchDialog
          courseId={courseId}
          topic={finding}
          onClose={() => setFinding(null)}
          onCreated={() => {
            setFinding(null);
            setCreation(null);
          }}
        />
      )}
    </div>
  );
}

/** Flattens the forest depth-first into rows that keep the structure via parent ids. */
function flattenForest(forest: CompetencyNode[]): Row[] {
  const rows: Row[] = [];
  const walk = (node: CompetencyNode, parent: number | null, number: string) => {
    if (node.goal.id == null) return;
    rows.push({
      id: node.goal.id,
      parent,
      number,
      goal: node.goal,
      role: node.role,
      session: sessionTitleOf(node.goal),
    });
    // Under a topic the skills come first and the sub-skills in no skill after them, so the
    // numbering follows the order the table shows them in: skills, then the "Sub-skills without a
    // skill" group.
    const children =
      node.role === "topic"
        ? [
            ...node.children.filter((child) => child.role === "capability"),
            ...node.children.filter((child) => child.role !== "capability"),
          ]
        : node.children;
    for (let index = 0; index < children.length; index++) {
      walk(children[index], node.goal.id, `${number}.${index + 1}`);
    }
  };
  for (let index = 0; index < forest.length; index++) {
    walk(forest[index], null, `${index + 1}`);
  }
  return rows;
}

function HeaderCell({
  column,
  dragged,
  dropSide,
  onDragStart,
  onDragOver,
  onDrop,
  onDragEnd,
  minWidth,
  onResize,
  filterActive,
  popoverOpen,
  onTogglePopover,
  popover,
}: {
  column: (typeof COLUMNS)[number];
  /** This header is the one being dragged to a new place. */
  dragged: boolean;
  /** The edge a dragged header would land on, when it hovers this one. */
  dropSide: "before" | "after" | null;
  /** Absent for the learning-goal column, which stays first. */
  onDragStart?: () => void;
  /** Absent while no header is being dragged. */
  onDragOver?: (side: "before" | "after") => void;
  onDrop: () => void;
  onDragEnd: () => void;
  minWidth: number;
  /** The new width in pixels, or `null` to return to the starting width. */
  onResize: (width: number | null) => void;
  filterActive: boolean;
  popoverOpen: boolean;
  onTogglePopover: () => void;
  popover: ReactNode;
}) {
  const label = (
    <span className="px-1 py-0.5 text-xs font-semibold text-hestia-text-muted">
      {column.label}
    </span>
  );
  // Set while the edge is dragged, so the drag doesn't also pick the header up to move it.
  const resizing = useRef(false);
  return (
    <div
      role="columnheader"
      draggable={onDragStart != null}
      onDragStart={(e) => {
        if (!onDragStart || resizing.current) {
          e.preventDefault();
          return;
        }
        e.dataTransfer.effectAllowed = "move";
        e.dataTransfer.setData("text/plain", column.label);
        onDragStart();
      }}
      onDragOver={(e) => {
        if (!onDragOver) return;
        e.preventDefault();
        e.dataTransfer.dropEffect = "move";
        const rect = e.currentTarget.getBoundingClientRect();
        onDragOver(e.clientX < rect.left + rect.width / 2 ? "before" : "after");
      }}
      onDrop={(e) => {
        e.preventDefault();
        onDrop();
      }}
      onDragEnd={onDragEnd}
      title={onDragStart ? "Drag to move this column" : undefined}
      className={`relative flex min-w-0 items-center py-2 ${column.key === "text" ? "px-2.5" : "px-2"} ${
        onDragStart ? "cursor-grab active:cursor-grabbing" : ""
      } ${dragged ? "opacity-40" : ""}`}
    >
      <div className="flex min-w-0 items-center gap-0.5 overflow-hidden">
      {label}
      {column.filterKeys && (
        <button
          type="button"
          onClick={onTogglePopover}
          aria-label={`Filter ${column.label}`}
          aria-expanded={popoverOpen}
          className={`flex h-5.5 w-5.5 items-center justify-center rounded-md transition hover:bg-hestia-text/5 ${
            filterActive
              ? "text-hestia-primary"
              : "text-hestia-text-muted hover:text-hestia-text"
          }`}
        >
          <svg
            viewBox="0 0 20 20"
            fill={filterActive ? "currentColor" : "none"}
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="h-3 w-3"
          >
            <path d="M2.5 4h15l-6 7v5l-3 1.5V11z" />
          </svg>
        </button>
      )}
      </div>
      {popover}
      {dropSide && (
        <span
          aria-hidden="true"
          className={`pointer-events-none absolute inset-y-1 z-20 w-0.5 rounded-full bg-hestia-primary ${
            dropSide === "before" ? "-left-px" : "-right-px"
          }`}
        />
      )}
      {/* The column's right edge; a double click returns it to its starting width. */}
      <span
        role="separator"
        aria-orientation="vertical"
        aria-label={`Resize ${column.label}`}
        title="Drag to resize, double-click to reset"
        onMouseDown={(e) => e.preventDefault()}
        onPointerDown={(e) => {
          e.preventDefault();
          e.stopPropagation();
          const handle = e.currentTarget;
          const startX = e.clientX;
          const startWidth = handle.parentElement!.getBoundingClientRect().width;
          resizing.current = true;
          handle.setPointerCapture(e.pointerId);
          const move = (ev: PointerEvent) =>
            onResize(Math.max(minWidth, Math.round(startWidth + ev.clientX - startX)));
          const end = () => {
            resizing.current = false;
            handle.removeEventListener("pointermove", move);
            handle.removeEventListener("pointerup", end);
            handle.removeEventListener("pointercancel", end);
          };
          handle.addEventListener("pointermove", move);
          handle.addEventListener("pointerup", end);
          handle.addEventListener("pointercancel", end);
        }}
        onClick={(e) => e.stopPropagation()}
        onDoubleClick={() => onResize(null)}
        className="absolute inset-y-0 -right-1.5 z-10 flex w-3 cursor-col-resize touch-none justify-center after:my-2 after:w-px after:bg-hestia-border after:transition hover:after:w-0.5 hover:after:bg-hestia-primary"
      />
    </div>
  );
}

/**
 * Toolbar menu choosing which attribute columns the grid shows. The learning-goal column is listed
 * but locked, since the tree's carets live in it. Moving and resizing happen on the headers.
 */
/**
 * The table's reading settings in one menu: short or full goal names, and which columns show. Both
 * are set once and left alone, so they sit behind a button instead of taking toolbar space.
 */
function DisplayMenu({
  fullWording,
  onChangeWording,
  prefs,
  open,
  onToggleOpen,
  onClose,
  onToggleHidden,
  onReset,
}: {
  fullWording: boolean;
  onChangeWording: (full: boolean) => void;
  prefs: ColumnPrefs;
  open: boolean;
  onToggleOpen: () => void;
  onClose: () => void;
  onToggleHidden: (key: AttributeKey) => void;
  onReset: () => void;
}) {
  const customised =
    prefs.hidden.length > 0 ||
    Object.keys(prefs.widths).length > 0 ||
    prefs.order.some((key, i) => key !== ATTRIBUTE_KEYS[i]);
  return (
    <div className="relative">
      <Button
        variant="neutral"
        className={`h-9 ${open ? "border-hestia-primary" : ""}`}
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={onToggleOpen}
      >
        <SlidersIcon />
        Display
        {prefs.hidden.length > 0 && (
          <span className="text-xs text-hestia-text-muted">({prefs.hidden.length} hidden)</span>
        )}
        <svg
          viewBox="0 0 20 20"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
          className={`h-3.5 w-3.5 text-hestia-text-muted transition-transform ${open ? "rotate-180" : ""}`}
        >
          <path d="M5.5 8l4.5 4.5L14.5 8" />
        </svg>
      </Button>
      {open && (
        <AnchoredPopover
          alignRight
          onClose={onClose}
          className="flex w-64 flex-col gap-3 rounded-lg border border-hestia-border bg-hestia-surface p-3 shadow-lg"
        >
          <div role="dialog" aria-label="Display options" className="flex min-h-0 flex-1 flex-col gap-3">
            <div>
              <p className="mb-1.5 text-xs font-semibold text-hestia-text-muted">Goal names</p>
              <WordingSwitch full={fullWording} onChange={onChangeWording} />
            </div>
            <div className="flex min-h-0 flex-col">
              <p className="mb-1 text-xs font-semibold text-hestia-text-muted">Columns</p>
              <div className="-mx-1.5 min-h-0 flex-1 overflow-y-auto">
                {(["text", ...prefs.order] as ColumnKey[]).map((key) => {
                  const locked = key === "text";
                  return (
                    <label
                      key={key}
                      className={`flex items-center gap-2 rounded-md px-1.5 py-1 text-sm text-hestia-text ${
                        locked ? "opacity-60" : "cursor-pointer hover:bg-hestia-text/5"
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={locked || !prefs.hidden.includes(key as AttributeKey)}
                        disabled={locked}
                        onChange={() => onToggleHidden(key as AttributeKey)}
                        className="h-3.5 w-3.5 shrink-0 accent-hestia-primary"
                      />
                      {COLUMN_BY_KEY.get(key)!.label}
                    </label>
                  );
                })}
              </div>
              <p className="mt-1.5 text-xs leading-snug text-hestia-text-muted">
                Drag a header to move its column, or its right edge to resize it.
              </p>
            </div>
            <div className="flex justify-between gap-2 border-t border-hestia-border pt-2">
              <button
                type="button"
                onClick={onReset}
                disabled={!customised}
                className="text-xs font-semibold text-hestia-primary transition hover:text-hestia-primary-hover disabled:cursor-not-allowed disabled:opacity-50"
              >
                Reset columns
              </button>
              <button
                type="button"
                onClick={onClose}
                className="text-xs font-semibold text-hestia-primary transition hover:text-hestia-primary-hover"
              >
                Done
              </button>
            </div>
          </div>
        </AnchoredPopover>
      )}
    </div>
  );
}

function AppendKnob({
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

function GridRow({
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
  // branch tiers (topic / capability / skill) and gaps stand out.
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
function SessionName({
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

/** A goal's kind, or how it came about when a person or the AI added it rather than extraction. */
function kindLabel(row: Row): string | null {
  if (row.goal.creationProvenance === "WIZARD_AI_SUBTREE") return "AI-inferred";
  if (row.goal.creationProvenance === "USER_CREATED") return "Manual";
  return row.goal.kind && !isGrouping(row.role) ? titleCase(row.goal.kind) : null;
}

function KindChip({ row }: { row: Row }) {
  const label = kindLabel(row);
  return label ? <Chip tone="neutral">{label}</Chip> : null;
}

/**
 * The table's visible attribute columns laid out for a diagram box: coverage and kind as chips,
 * Bloom and SOLO side by side under their names, and the source beneath. A column hidden in the
 * table is hidden here too; the levels and the source stay editable and openable as in a row.
 */
function DiagramAttributes({
  row,
  columns,
  levelFlag,
  openLevel,
  onToggleLevel,
  onCloseLevel,
  onUpdate,
  sourceOpen,
  onOpenSource,
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
}) {
  const coverage = coverageOf(row.goal);
  const showCoverage = columns.includes("coverage") && coverage != null && coverage !== "unknown";
  const showKind = columns.includes("kind") && kindLabel(row) != null;
  // A topic is a noun phrase with no level of its own.
  const scales = (["bloom", "solo"] as const).filter(
    (scale) => columns.includes(scale) && row.role !== "topic",
  );
  const showSource = columns.includes("source") && row.goal.sources?.[0] != null;
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
          <SourceChip row={row} open={sourceOpen} onOpen={(trigger) => onOpenSource(row, trigger)} />
        </div>
      )}
    </div>
  );
}

/**
 * A goal's own source as a control that opens it in the PDF panel: the document and page, with the
 * session beneath. Renders nothing for a goal without a source.
 */
function SourceChip({
  row,
  disabled = false,
  open,
  onOpen,
}: {
  row: Row;
  disabled?: boolean;
  /** This goal's source is the one shown in the PDF panel. */
  open: boolean;
  onOpen: (trigger: HTMLElement) => void;
}) {
  const source = row.goal.sources?.[0];
  if (!source) return null;
  // The document names the source; the session is where in the course it sits. A session named
  // exactly like its file (one lecture per PDF) would only repeat the label, so it is left out.
  const documentLabel = source.displayName || source.filename || "Source document";
  const sessionLabel = row.session && row.session !== documentLabel ? row.session : null;
  // A figure source rests on the AI's description of a slide image rather than on quoted text.
  const figure = source.evidenceKind === "FIGURE";
  return (
    <button
      type="button"
      disabled={disabled}
      aria-pressed={open}
      title={`${documentLabel}${source.page ? ` · page ${source.page}` : ""}${
        sessionLabel ? `\nSession: ${sessionLabel}` : ""
      }${
        figure && source.figureDescription
          ? `\nFigure (AI description): ${source.figureDescription}`
          : ""
      }`}
      onClick={(e) => {
        e.stopPropagation();
        onOpen(e.currentTarget);
      }}
      // The row itself opens the goal on click and on Enter/Space, so this control has to
      // keep both to itself.
      onKeyDown={(e) => e.stopPropagation()}
      className={`flex max-w-full min-w-0 flex-col items-start rounded-md border px-1.5 py-0.5 text-left transition ${
        open
          ? "border-[color-mix(in_srgb,var(--hestia-primary)_40%,transparent)] bg-hestia-primary-muted text-hestia-primary"
          : "border-hestia-border/60 bg-hestia-text/3 hover:border-[color-mix(in_srgb,var(--hestia-primary)_40%,transparent)] hover:text-hestia-text"
      }`}
    >
      <span className="flex max-w-full min-w-0 items-center gap-1">
        {figure && (
          <svg
            viewBox="0 0 20 20"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
            role="img"
            aria-label="Figure source"
            className="h-3.5 w-3.5 shrink-0"
          >
            <rect x="3" y="4" width="14" height="12" rx="2" />
            <path d="M3 13l4-4 3 3 2-2 5 5" />
            <circle cx="13" cy="8" r="1.2" />
          </svg>
        )}
        <span className="min-w-0 truncate">{documentLabel}</span>
        {source.page != null && (
          <span className="shrink-0 tabular-nums">· p. {source.page}</span>
        )}
      </span>
      {sessionLabel && (
        <span className="max-w-full truncate opacity-75">
          Session: {sessionLabel}
        </span>
      )}
    </button>
  );
}

/**
 * A goal's Bloom or SOLO level as a cell: the level name, or a "Set" placeholder for a goal nobody
 * classified. Clicking it opens the taxonomy's ladder, each step with its dot scale and description.
 */
function LevelCell({
  scale,
  value,
  interactive,
  open,
  onToggle,
  onClose,
  onSelect,
}: {
  scale: LevelScale;
  value: string | null | undefined;
  interactive: boolean;
  open: boolean;
  onToggle: () => void;
  onClose: () => void;
  /** Receives the API enum value of the chosen step. */
  onSelect: (value: string) => void;
}) {
  const meta = LEVEL_SCALES[scale];
  const term = value ? titleCase(value) : null;
  const ladder = Object.keys(meta.desc);
  const index = term == null ? -1 : ladder.indexOf(term);
  const reading = term && (
    <span
      className="flex min-w-0 items-center gap-1"
      aria-label={`${meta.label} level ${index + 1} of ${ladder.length}: ${term}`}
    >
      <LevelPips index={index} steps={ladder.length} />
      <span className="truncate" aria-hidden="true">{term}</span>
    </span>
  );
  if (!interactive) {
    return reading ? <span className="flex text-xs text-hestia-text">{reading}</span> : null;
  }
  // Escape has to reach the popover's own listener, so only the row's activation keys are held back.
  const keepRowKeys = (e: ReactKeyboardEvent) => {
    if (e.key !== "Escape") e.stopPropagation();
  };
  return (
    <div className="relative inline-flex max-w-full">
      <button
        type="button"
        aria-expanded={open}
        title={term ? `${meta.label}: ${term}` : `Set the ${meta.label} level`}
        onClick={(e) => {
          e.stopPropagation();
          onToggle();
        }}
        onKeyDown={keepRowKeys}
        className={`-mx-1 flex max-w-[calc(100%+0.5rem)] min-w-0 items-center rounded-md px-1 py-0.5 text-xs transition ${
          term
            ? `text-hestia-text hover:bg-hestia-text/5 ${open ? "bg-hestia-text/5" : ""}`
            : "border border-dashed border-hestia-border text-hestia-text-muted hover:border-hestia-primary hover:text-hestia-primary"
        }`}
      >
        {reading || "Set"}
      </button>
      {open && (
        <AnchoredPopover
          onClose={onClose}
          className="flex w-72 flex-col rounded-lg border border-hestia-border bg-hestia-surface p-1.5 shadow-lg"
        >
          {/* The panel is portalled, but React still bubbles its events up to the row. */}
          <div
            onClick={(e) => e.stopPropagation()}
            onKeyDown={keepRowKeys}
            className="min-h-0 overflow-y-auto"
          >
            {ladder.map((step, i) => (
              <button
                key={step}
                type="button"
                aria-current={i === index}
                onClick={() => {
                  if (i !== index) onSelect(toEnum(step));
                  onClose();
                }}
                className={`flex w-full items-start gap-2.5 rounded-md px-2 py-1.5 text-left transition hover:bg-hestia-text/5 ${
                  i === index ? "bg-hestia-primary-muted" : ""
                }`}
              >
                <span className="mt-2">
                  <LevelPips index={i} steps={ladder.length} />
                </span>
                <span className="min-w-0">
                  <span className="block text-sm font-medium text-hestia-text">{step}</span>
                  <span className="block text-xs leading-snug text-hestia-text-muted">
                    {meta.desc[step as keyof typeof meta.desc]}
                  </span>
                </span>
              </button>
            ))}
          </div>
        </AnchoredPopover>
      )}
    </div>
  );
}

/**
 * The table's chip styles. Colour carries meaning only twice: the tier chips take the hue of their
 * rail (topic in primary, skill and sub-skill in accent), and a problem takes warning. Everything
 * else is a quiet neutral outline. The tints mix with the surface rather than going transparent so
 * the label keeps its contrast on a zebra row too.
 */
const CHIP_TONES = {
  topic: {
    color: "var(--hestia-primary)",
    backgroundColor: "color-mix(in srgb, var(--hestia-primary) 14%, var(--hestia-surface))",
    borderColor: "transparent",
  },
  capability: {
    color: "var(--hestia-accent)",
    backgroundColor: "color-mix(in srgb, var(--hestia-accent) 15%, var(--hestia-surface))",
    borderColor: "transparent",
  },
  skill: {
    color: "var(--hestia-accent)",
    backgroundColor: "transparent",
    borderColor: "color-mix(in srgb, var(--hestia-accent) 50%, var(--hestia-surface))",
  },
  gap: {
    color: "var(--hestia-danger)",
    backgroundColor: "color-mix(in srgb, var(--hestia-danger) 12%, var(--hestia-surface))",
    borderColor: "transparent",
  },
  neutral: {
    color: "var(--hestia-text-muted)",
    backgroundColor: "transparent",
    borderColor: "var(--hestia-border)",
  },
  // Warning is too light to be text, so the label stays in the text colour on the warning tint.
  warning: {
    color: "var(--hestia-text)",
    backgroundColor: "color-mix(in srgb, var(--hestia-warning) 22%, var(--hestia-surface))",
    borderColor: "transparent",
  },
} satisfies Record<string, CSSProperties>;

type ChipTone = keyof typeof CHIP_TONES;

function Chip({
  tone,
  title,
  children,
}: {
  tone: ChipTone;
  title?: string;
  children: ReactNode;
}) {
  return (
    <span
      title={title}
      className="inline-flex h-[22px] items-center gap-1 whitespace-nowrap rounded-md border px-2 text-xs font-medium"
      style={CHIP_TONES[tone]}
    >
      {children}
    </span>
  );
}

/** A goal's tier as a chip in its rail's hue; knowledge, the plain leaf tier, is plain text. */
function TierChip({ role }: { role: CompetencyRole }) {
  const label = COMPETENCY_ROLE_META[role].label;
  if (role === "knowledge") return <span className="text-xs text-hestia-text-muted">{label}</span>;
  return <Chip tone={role}>{label}</Chip>;
}

/**
 * Where a level sits on its taxonomy's ladder, as a row of squares. Neutral on purpose: a higher
 * Bloom or SOLO level is not better, so a hue ramp would suggest a judgement the scale doesn't make.
 */
function LevelPips({ index, steps }: { index: number; steps: number }) {
  return (
    <span className="flex shrink-0 gap-0.5" aria-hidden="true">
      {Array.from({ length: steps }, (_, i) => (
        <span
          key={i}
          className="h-[5px] w-[5px] rounded-[1px]"
          style={{
            backgroundColor:
              i <= index
                ? "color-mix(in srgb, var(--hestia-text) 75%, var(--hestia-surface))"
                : "var(--hestia-border)",
          }}
        />
      ))}
    </span>
  );
}

/**
 * Segmented control switching the grid between its two layouts. Follows the styleguide's toggle:
 * one surface track, the selected segment filled with primary. It shares the toolbar's 36px height
 * and 8px radius so the row reads as one set of controls.
 */
function LayoutSwitch({
  layout,
  onChange,
}: {
  layout: Layout;
  onChange: (layout: Layout) => void;
}) {
  const options: { key: Layout; label: string; icon: ReactNode }[] = [
    { key: "table", label: "Table", icon: <TableIcon /> },
    { key: "diagram", label: "Diagram", icon: <DiagramIcon /> },
  ];
  return (
    <div
      role="tablist"
      aria-label="Tree layout"
      className="inline-flex h-9 gap-0.5 rounded-md border border-hestia-border bg-hestia-surface p-[3px]"
    >
      {options.map((option) => {
        const active = layout === option.key;
        return (
          <button
            key={option.key}
            type="button"
            role="tab"
            aria-selected={active}
            onClick={() => onChange(option.key)}
            className={`inline-flex items-center gap-1.5 rounded-[6px] px-3 text-sm font-medium transition ${
              active
                ? "bg-hestia-primary text-hestia-on-primary"
                : "text-hestia-text-muted hover:text-hestia-text"
            }`}
          >
            {option.icon}
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

/** Switch between short labels and every goal's full wording in the goal column. */
function WordingSwitch({
  full,
  onChange,
}: {
  full: boolean;
  onChange: (full: boolean) => void;
}) {
  const options = [
    { full: false, label: "Short" },
    { full: true, label: "Full" },
  ];
  return (
    <div
      role="radiogroup"
      aria-label="Goal names"
      className="flex h-8 gap-0.5 rounded-md border border-hestia-border bg-hestia-surface p-[3px]"
    >
      {options.map((option) => {
        const active = full === option.full;
        return (
          <button
            key={option.label}
            type="button"
            role="radio"
            aria-checked={active}
            onClick={() => onChange(option.full)}
            className={`flex-1 rounded-[5px] text-xs font-medium transition ${
              active
                ? "bg-hestia-primary text-hestia-on-primary"
                : "text-hestia-text-muted hover:text-hestia-text"
            }`}
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

/** Icons for the layout switch. Sized to sit inline with the label text. */
function DiagramIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-4 w-4"
    >
      <rect x="7.5" y="2.5" width="5" height="4" rx="1" />
      <rect x="2" y="13.5" width="5" height="4" rx="1" />
      <rect x="13" y="13.5" width="5" height="4" rx="1" />
      <path d="M10 6.5v3M10 9.5H4.5v4M10 9.5h5.5v4" />
    </svg>
  );
}

function TableIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-4 w-4"
    >
      <rect x="3" y="4" width="14" height="12" rx="1.5" />
      <path d="M3 8h14M8 8v8" />
    </svg>
  );
}

/** Chevrons pointing apart (expand) or together (collapse), matching the button's current action. */
function FoldIcon({ collapse }: { collapse: boolean }) {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-4 w-4"
    >
      {collapse ? (
        <path d="M6.5 3.5L10 7l3.5-3.5M6.5 16.5L10 13l3.5 3.5" />
      ) : (
        <path d="M6.5 6.5L10 3l3.5 3.5M6.5 13.5L10 17l3.5-3.5" />
      )}
    </svg>
  );
}

function SlidersIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="h-4 w-4"
    >
      <path d="M17 4.5h-5.5M8 4.5H3M17 10h-7M6.5 10H3M17 15.5h-3.5M10 15.5H3M11.5 3v3M6.5 8.5v3M13.5 14v3" />
    </svg>
  );
}

function CrossIcon() {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.5"
      strokeLinecap="round"
      aria-hidden="true"
      className="h-3 w-3"
    >
      <path d="M5 5l10 10M15 5L5 15" />
    </svg>
  );
}
