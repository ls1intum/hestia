import {
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactElement,
  type ReactNode,
} from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client.ts";
import type { LearningGoal } from "../api/client.ts";
import CompetencyGoalModal from "./CompetencyGoalModal.tsx";
import CompetencyCreationField from "./CompetencyCreationField.tsx";
import Button from "./Button.tsx";
import FilterPopover from "./FilterPopover.tsx";
import {
  COMPETENCY_ROLE_META,
  buildCompetencyForest,
  childGoalsOf,
  generatedChildCount,
  supportingOutcomesOf,
  titleCase,
  type CompetencyNode,
  type CompetencyRole,
} from "../lib/goals.ts";

/**
 * The competency tree as an Excel-like tree-grid: the Skill → Sub-skill hierarchy lives in the
 * first column with expand/collapse carets, while every goal attribute becomes a proper column
 * with a funnel filter (multi-select checkboxes) and hierarchy-preserving sorting (siblings are
 * sorted within their parent).
 *
 * The third tier is deliberately absent. Knowledge is evidence for the sub-skill above it, so it
 * is shown in that sub-skill's detail modal instead of as rows here — the grid stays the review
 * list of the things an instructor actually judges. The Items column still counts what hides
 * underneath, and search still reaches the hidden wording (see `evidenceText`). The map keeps
 * rendering all three tiers, because seeing the branch is that view's job.
 *
 * Filter semantics: matching rows stay in their tree position; ancestors of a match that don't
 * match themselves are shown dimmed as context-only rows. While a filter or search is active the
 * tree is fully unfolded so no match can hide inside a collapsed branch.
 *
 * It renders as a CSS grid (not a <table>) so whole rows can animate in the map view's language:
 * opening a branch cascades its rows in with a light overshoot while the rows below glide down
 * (FLIP), and collapsing a branch fades its rows out before the survivors slide up.
 */

/** One goal flattened out of the forest, with the tree structure kept via parent ids. */
type Row = {
  id: number;
  parent: number | null;
  number: string;
  goal: LearningGoal;
  role: CompetencyRole;
  childCount: number;
  /**
   * Every session this row covers, sorted: its own plus all of its descendants'. Skills and
   * sub-skills are synthesised and carry no hierarchy themselves, so their sessions are exactly
   * the ones their grounded children came from.
   */
  sessions: string[];
};

type FilterKey = "role" | "kind" | "session";
type SortKey = "text" | "items" | "session";
type SortState = { key: SortKey; dir: 1 | -1 } | null;
type CreationTier = 1 | 2 | 3;
type CreationState = {
  key: string;
  tier: CreationTier;
  parentGoalId: number | null;
  text: string;
};

// Shared grid template so the sticky header row and every body row line their columns up: a
// flexible learning-goal column, then fixed attribute columns. Kept in one place so header and
// rows can never drift apart.
// Session carries full titles (often several on one skill row), so it takes a share of the free
// space rather than a fixed width — otherwise the goal column swallows everything.
const GRID_COLS =
  "minmax(240px,1fr) 108px 96px 72px minmax(200px,0.55fr)";

// "AI_INFERRED" is a synthetic kind value derived from a goal's WIZARD_AI_SUBTREE provenance, so the
// Kind column and its filter surface AI-generated goals without a separate GoalKind enum on the server.
const AI_INFERRED_KIND = "AI_INFERRED";
const MANUAL_KIND = "MANUAL";
const KIND_ORDER = ["EXPLICIT", "IMPLICIT", AI_INFERRED_KIND, MANUAL_KIND];
const ROLE_ORDER: CompetencyRole[] = [
  "competency",
  "sub-skill",
  "knowledge",
  "gap",
];

const prefersReducedMotion = () =>
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/**
 * The filterable values of a row in one column. Session is the only multi-valued column: a skill
 * covers every session its children were grounded in, and filtering for any one of them keeps it.
 */
function valuesOf(row: Row, key: FilterKey): string[] {
  switch (key) {
    case "role":
      return [row.role];
    case "kind":
      if (row.goal.creationProvenance === "WIZARD_AI_SUBTREE")
        return [AI_INFERRED_KIND];
      if (row.goal.creationProvenance === "USER_CREATED") return [MANUAL_KIND];
      return [row.goal.kind ?? ""];
    case "session":
      return row.sessions;
  }
}

/** Session is preferred; exercise titles preserve useful context for exercise-only goals. */
function sessionTitleOf(goal: LearningGoal): string {
  return goal.hierarchy?.session ?? goal.hierarchy?.exercise ?? "";
}

function displayedGoalLabel(goal: LearningGoal): string {
  return goal.shortLabel ?? goal.text ?? "";
}

/** Human label for a raw column value (role names, title-cased enums). */
function displayValue(key: FilterKey, value: string): string {
  if (key === "role")
    return COMPETENCY_ROLE_META[value as CompetencyRole].label;
  if (key === "session") return value || "—";
  if (value === AI_INFERRED_KIND) return "AI-inferred";
  if (value === MANUAL_KIND) return "Manual";
  return value ? titleCase(value) : "—";
}

const COLUMNS: {
  key: FilterKey | "text" | "items";
  label: string;
  sortKey?: SortKey;
  filterKey?: FilterKey;
  alignRight?: boolean;
}[] = [
  { key: "text", label: "Learning goal", sortKey: "text" },
  { key: "role", label: "Level", filterKey: "role" },
  { key: "kind", label: "Kind", filterKey: "kind" },
  { key: "items", label: "Items", sortKey: "items", alignRight: true },
  {
    key: "session",
    label: "Session",
    sortKey: "session",
    filterKey: "session",
  },
];

export default function CompetencyTree({
  courseId,
  goals,
  onUpdate,
  onDelete,
  viewSwitch,
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
  /** The page's Table/Map switch — it shares this view's toolbar row instead of a row of its own. */
  viewSwitch?: ReactNode;
}) {
  const queryClient = useQueryClient();
  const [creation, setCreation] = useState<CreationState | null>(null);
  const createMutation = useMutation({
    mutationFn: async (vars: CreationState) => {
      if (vars.tier === 1) {
        const result = await api.POST(
          "/api/courses/{courseId}/learning-goals/terminal",
          { params: { path: { courseId } }, body: { text: vars.text } },
        );
        if (!result.data) {
          throw new Error(
            result.response.status === 409
              ? "A skill with that wording already exists."
              : "Could not add the skill.",
          );
        }
        return result.data;
      }
      const result = await api.POST(
        "/api/courses/{courseId}/learning-goals/{goalId}/children",
        {
          params: { path: { courseId, goalId: vars.parentGoalId! } },
          body: { text: vars.text },
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
  const submitCreation = () => {
    if (!creation || creation.text.trim() === "") return;
    createMutation.mutate(
      { ...creation, text: creation.text.trim() },
      { onSuccess: () => setCreation(null) },
    );
  };

  const forest = useMemo(() => buildCompetencyForest(goals), [goals]);
  const rows = useMemo(() => flattenForest(forest), [forest]);
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

  // Knowledge under a sub-skill is evidence, and it now lives in that sub-skill's detail modal
  // rather than as rows here — the grid is the review list of skills and sub-skills. Knowledge in
  // any other position (a legacy node hanging straight off a skill) keeps its row, because no
  // modal would show it and the goal would otherwise become unreachable.
  const hiddenIds = useMemo(() => {
    const hidden = new Set<number>();
    for (const row of rows) {
      if (row.role !== "knowledge" || row.parent == null) continue;
      if (byId.get(row.parent)?.role === "sub-skill") hidden.add(row.id);
    }
    return hidden;
  }, [rows, byId]);

  // The wording of the knowledge a row hides, so a search term that only occurs down there still
  // finds the sub-skill holding it instead of silently matching nothing.
  const evidenceText = useMemo(() => {
    const map = new Map<number, string>();
    for (const row of rows) {
      if (!hiddenIds.has(row.id) || row.parent == null) continue;
      const text = `${row.goal.shortLabel ?? ""} ${row.goal.text ?? ""}`.toLowerCase();
      map.set(row.parent, `${map.get(row.parent) ?? ""} ${text}`);
    }
    return map;
  }, [rows, hiddenIds]);

  // How many children a row still shows, which is what decides whether it gets a caret at all.
  const visibleChildCount = useMemo(() => {
    const map = new Map<number, number>();
    for (const row of rows) {
      if (row.parent == null || hiddenIds.has(row.id)) continue;
      map.set(row.parent, (map.get(row.parent) ?? 0) + 1);
    }
    return map;
  }, [rows, hiddenIds]);

  // Everything starts collapsed: the skills alone are the overview, and opening one is the reader's
  // first deliberate step rather than a state they arrive in.
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [search, setSearch] = useState("");
  const [filters, setFilters] = useState<Record<FilterKey, Set<string>>>({
    role: new Set(),
    kind: new Set(),
    session: new Set(),
  });
  const [sort, setSort] = useState<SortState>(null);
  const [openFilter, setOpenFilter] = useState<FilterKey | null>(null);
  // Clicking a row opens the same classification overlay the map view uses. The modal only reads
  // the node's goal and role, so a row's flat data is enough to build one.
  const [detail, setDetail] = useState<{
    goal: LearningGoal;
    role: CompetencyRole;
  } | null>(null);
  // The goal the modal was drilled into from, so a knowledge goal opened out of the evidence list
  // can hand the reader back to its sub-skill instead of dropping them out of the modal entirely.
  const [detailParent, setDetailParent] = useState<{
    goal: LearningGoal;
    role: CompetencyRole;
  } | null>(null);
  const openDetail = (row: Row) => {
    setDetailParent(null);
    setDetail({ goal: row.goal, role: row.role });
  };
  const onOpenGoal = (goal: LearningGoal) => {
    setDetailParent(detail);
    setDetail({ goal, role: "knowledge" });
  };
  const closeDetail = () => {
    setDetail(null);
    setDetailParent(null);
  };

  const filtering =
    search.trim() !== "" || Object.values(filters).some((s) => s.size > 0);

  // Rows surviving the filters, plus their non-matching ancestors as dimmed context. `evidenceIds`
  // are the rows that only matched through the knowledge they hide, which the row then says out
  // loud — otherwise a hit with no visible cause looks like a bug.
  const { matchIds, contextIds, evidenceIds } = useMemo(() => {
    if (!filtering)
      return {
        matchIds: null as Set<number> | null,
        contextIds: new Set<number>(),
        evidenceIds: new Set<number>(),
      };
    const needle = search.trim().toLowerCase();
    const evidence = new Set<number>();
    const matchesRow = (row: Row): boolean => {
      if (needle) {
        const inOwnText = [row.goal.shortLabel, row.goal.text].some((value) =>
          (value ?? "").toLowerCase().includes(needle),
        );
        const inEvidence =
          !inOwnText && (evidenceText.get(row.id) ?? "").includes(needle);
        if (!inOwnText && !inEvidence) return false;
        if (inEvidence) evidence.add(row.id);
      }
      for (const key of Object.keys(filters) as FilterKey[]) {
        const set = filters[key];
        if (set.size > 0 && !valuesOf(row, key).some((v) => set.has(v)))
          return false;
      }
      return true;
    };
    const matches = new Set(
      rows.filter((row) => !hiddenIds.has(row.id) && matchesRow(row)).map((r) => r.id),
    );
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
      evidenceIds: evidence,
    };
  }, [rows, byId, hiddenIds, evidenceText, search, filters, filtering]);

  // Only offer filter values that actually occur — counted over the rows the grid still shows, so
  // no filter can promise a value that has no row to land on.
  const filterOptions = useMemo(() => {
    const visible = rows.filter((r) => !hiddenIds.has(r.id));
    const present = (key: FilterKey) =>
      new Set(visible.flatMap((r) => valuesOf(r, key)).filter((v) => v !== ""));
    const ordered = (order: string[], values: Set<string>) =>
      order.filter((v) => values.has(v));
    return {
      role: ordered(ROLE_ORDER, present("role")),
      kind: ordered(KIND_ORDER, present("kind")),
      session: [...present("session")].sort((a, b) => a.localeCompare(b)),
    };
  }, [rows, hiddenIds]);

  const sortSiblings = (siblings: Row[]): Row[] => {
    if (!sort) return siblings;
    const { key, dir } = sort;
    const rank = (row: Row): string | number => {
      switch (key) {
        case "text":
          return displayedGoalLabel(row.goal);
        case "items":
          return row.childCount;
        case "session":
          return row.sessions[0] ?? "";
      }
    };
    return [...siblings].sort((a, b) => {
      const va = rank(a);
      const vb = rank(b);
      return (va < vb ? -1 : va > vb ? 1 : 0) * dir;
    });
  };

  // ── Row animation (map-view language). A single FLIP pass after each render: surviving rows
  // (measured last render) glide to their new position, newly revealed rows cascade in with a
  // light overshoot. Collapsing is handled imperatively below so its rows can fade out first. ──
  const containerRef = useRef<HTMLDivElement>(null);
  // Positions are layout-relative (`offsetTop`), not viewport-relative: the grid sits in its own
  // scroll container, so a rect measured before a scroll differs by the scrolled distance and
  // would make every surviving row glide in from nowhere.
  const prevTops = useRef<Map<number, number>>(new Map());
  const firstLayout = useRef(true);
  // Which newly-revealed rows may cascade in on the next layout: the descendants of a branch just
  // opened, or "all" for expand-all. Empty for every other change (filter / search / sort), so
  // those rows simply appear while the survivors glide — matching the map view's restraint. It is
  // consumed (reset to empty) after each layout pass.
  const enterIntent = useRef<Set<number> | "all">(new Set());

  useLayoutEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const reduce = prefersReducedMotion();
    const prev = prevTops.current;
    const intent = enterIntent.current;
    const next = new Map<number, number>();
    let enterIndex = 0;
    container.querySelectorAll<HTMLElement>("[data-goal-id]").forEach((el) => {
      const id = Number(el.dataset.goalId);
      const to = el.offsetTop;
      next.set(id, to);
      if (reduce || firstLayout.current) return;
      // Clear any leftover entrance state from an earlier cascade before deciding afresh.
      el.classList.remove("tree-row-in");
      el.style.animationDelay = "";
      const from = prev.get(id);
      if (from != null) {
        const dy = from - to;
        if (Math.abs(dy) > 1)
          el.animate(
            [{ transform: `translateY(${dy}px)` }, { transform: "none" }],
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
  });

  const descendantIds = (id: number): Set<number> => {
    const out = new Set<number>();
    const walk = (pid: number) => {
      for (const child of childrenOf.get(pid) ?? []) {
        out.add(child.id);
        walk(child.id);
      }
    };
    walk(id);
    return out;
  };

  const expand = (id: number) => {
    enterIntent.current = descendantIds(id);
    setExpanded((prev) => new Set(prev).add(id));
  };

  // Collapse: fade the currently-visible descendants out, then drop them from the tree (the FLIP
  // pass then slides the survivors up). Falls back to an instant collapse when motion is reduced.
  const collapse = (id: number) => {
    const remove = () =>
      setExpanded((prev) => {
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
      ...container.querySelectorAll<HTMLElement>("[data-goal-id]"),
    ].filter((el) => kill.has(Number(el.dataset.goalId)));
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

  const onToggle = (id: number) =>
    expanded.has(id) ? collapse(id) : expand(id);

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
      session: new Set(),
    });
  };

  const cycleSort = (key: SortKey) =>
    setSort((prev) =>
      prev?.key !== key
        ? { key, dir: 1 }
        : prev.dir === 1
          ? { key, dir: -1 }
          : null,
    );

  const parentIds = useMemo(
    () => rows.filter((r) => (visibleChildCount.get(r.id) ?? 0) > 0).map((r) => r.id),
    [rows, visibleChildCount],
  );
  const allOpen =
    parentIds.length > 0 && parentIds.every((id) => expanded.has(id));

  if (forest.length === 0) {
    return (
      <p className="rounded-xl border border-dashed border-hestia-border p-8 text-center text-sm text-hestia-text-muted">
        No competency tree was created during extraction for this course.
      </p>
    );
  }

  // Depth-first walk producing the visible rows: expansion state applies while browsing, filters
  // force the full path to every match open.
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
    const ordered = sortSiblings(siblings.filter((r) => !hiddenIds.has(r.id)));
    for (let i = 0; i < ordered.length; i++) {
      const row = ordered[i];
      const isMatch = !filtering || matchIds!.has(row.id);
      const isContext = filtering && contextIds.has(row.id);
      if (filtering && !isMatch && !isContext) continue;
      bodyRows.push(
        <GridRow
          key={row.id}
          row={row}
          depth={depth}
          zebra={rowIndex++ % 2 === 1}
          context={isContext}
          filtering={filtering}
          open={expanded.has(row.id)}
          childCount={visibleChildCount.get(row.id) ?? 0}
          evidenceMatch={evidenceIds.has(row.id)}
          onToggle={onToggle}
          onOpen={openDetail}
        />,
      );
      // A childless skill is still walked into so its "Add sub-skill" knob has somewhere to live.
      if (
        filtering ||
        expanded.has(row.id) ||
        ((visibleChildCount.get(row.id) ?? 0) === 0 && row.role === "competency")
      )
        walk(
          childrenOf.get(row.id) ?? [],
          depth + 1,
          row.id,
          row.role,
          trailing && i === ordered.length - 1,
        );
    }
    // Only two tiers are addable here — knowledge is added from its sub-skill's evidence list.
    if (!filtering && depth < 2) {
      const visibleSiblingCount = siblings.filter((row) => !hiddenIds.has(row.id)).length;
      const append =
        depth === 0
          ? { tier: 1 as const, label: "Add skill", color: "var(--hestia-primary)" }
          : depth === 1 && parentRole === "competency" && visibleSiblingCount < 5
            ? {
                tier: 2 as const,
                label: "Add sub-skill",
                color: "var(--hestia-accent)",
              }
            : null;
      if (append) {
        bodyRows.push(
          <AppendKnob
            key={`append-${append.tier}-${parentGoalId ?? "root"}`}
            depth={depth}
            label={append.label}
            color={append.color}
            last={trailing}
            active={creation?.key === `${append.tier}:${parentGoalId ?? "root"}`}
            value={creation?.text ?? ""}
            pending={createMutation.isPending}
            error={
              creation?.key === `${append.tier}:${parentGoalId ?? "root"}` &&
              createMutation.isError
                ? (createMutation.error as Error).message
                : undefined
            }
            onStart={() => beginCreation(append.tier, parentGoalId)}
            onChange={updateCreationText}
            onSubmit={submitCreation}
            onCancel={cancelCreation}
          />,
        );
      }
    }
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
    if (!column.filterKey) continue;
    for (const value of filters[column.filterKey]) {
      const key = column.filterKey;
      activeChips.push({
        label: column.label,
        value: displayValue(key, value),
        onRemove: () => toggleFilterValue(key, value),
      });
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3">
        {viewSwitch}
        <label className="relative flex min-w-48 max-w-xs flex-1 items-center">
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
            className="w-full rounded-sm border-[1.5px] border-hestia-border bg-hestia-surface py-1.5 pl-9 pr-3 text-sm text-hestia-text transition focus:border-hestia-primary focus:shadow-[0_0_0_3px_var(--hestia-primary-muted)] focus:outline-none"
          />
        </label>
        <span className="flex-1" />
        {sort ? (
          <button
            type="button"
            onClick={() => setSort(null)}
            className="text-xs font-medium text-hestia-primary underline transition hover:text-hestia-text"
          >
            Restore lecture order
          </button>
        ) : (
          <span className="text-xs font-medium text-hestia-text-muted">Lecture order</span>
        )}
        {!filtering && (
          <Button
            onClick={() => {
              if (allOpen) {
                setExpanded(new Set());
              } else {
                enterIntent.current = "all";
                setExpanded(new Set(parentIds));
              }
            }}
          >
            <FoldIcon collapse={allOpen} />
            {allOpen ? "Collapse all" : "Expand all"}
          </Button>
        )}
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

      <div className="overflow-hidden rounded-xl border border-hestia-border bg-hestia-surface shadow-sm">
        {/* pb-4 keeps the trailing append knob inside the scroll area instead of under its edge. */}
        <div className="max-h-[72vh] overflow-auto pb-4">
          <div
            role="table"
            aria-label="Competency tree"
            className="min-w-[780px]"
          >
            <div
              role="row"
              className="sticky top-0 z-10 grid rounded-t-xl border-b border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_4%,var(--hestia-surface))]"
              style={{ gridTemplateColumns: GRID_COLS }}
            >
              {COLUMNS.map((column) => (
                <HeaderCell
                  key={column.key}
                  column={column}
                  sort={sort}
                  onSort={cycleSort}
                  filterActive={
                    column.filterKey
                      ? filters[column.filterKey].size > 0
                      : false
                  }
                  popoverOpen={
                    column.filterKey != null && openFilter === column.filterKey
                  }
                  onTogglePopover={() =>
                    setOpenFilter((prev) =>
                      prev === column.filterKey ? null : column.filterKey!,
                    )
                  }
                  popover={
                    column.filterKey != null &&
                    openFilter === column.filterKey ? (
                      <FilterPopover
                        options={filterOptions[column.filterKey]}
                        selected={filters[column.filterKey]}
                        display={(v) => displayValue(column.filterKey!, v)}
                        alignRight={column.alignRight}
                        onToggle={(v) =>
                          toggleFilterValue(column.filterKey!, v)
                        }
                        onClear={() => {
                          clearFilter(column.filterKey!);
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
      {/* The modal always gets the freshest goal for its id, so in-modal edits survive refetches. */}
      <CompetencyGoalModal
        goal={detail ? (byId.get(detail.goal.id!)?.goal ?? detail.goal) : null}
        role={detail?.role}
        knowledge={childGoalsOf(forest, detail?.goal.id)}
        supportingOutcomes={supportingOutcomesOf(goals, detail?.goal.id)}
        generatedChildCount={generatedChildCount(forest, detail?.goal.id)}
        onClose={closeDetail}
        onUpdate={onUpdate}
        onDelete={onDelete}
        onOpenGoal={onOpenGoal}
        onBack={detailParent ? () => {
          setDetail(detailParent);
          setDetailParent(null);
        } : undefined}
        backLabel={
          detailParent
            ? displayedGoalLabel(detailParent.goal)
            : undefined
        }
      />
    </div>
  );
}

/**
 * Flattens the forest depth-first into rows that keep the structure via parent ids. Each walk
 * returns the sessions its subtree covers, which is how a synthesised skill or sub-skill — which
 * has no hierarchy of its own — ends up reporting the sessions of its grounded descendants.
 */
function flattenForest(forest: CompetencyNode[]): Row[] {
  const rows: Row[] = [];
  const walk = (node: CompetencyNode, parent: number | null, number: string): string[] => {
    if (node.goal.id == null) return [];
    const row: Row = {
      id: node.goal.id,
      parent,
      number,
      goal: node.goal,
      role: node.role,
      childCount: node.children.length,
      sessions: [],
    };
    rows.push(row);
    const sessions = new Set<string>();
    const own = sessionTitleOf(node.goal);
    if (own) sessions.add(own);
    for (let index = 0; index < node.children.length; index++) {
      const child = node.children[index];
      for (const session of walk(child, node.goal.id, `${number}.${index + 1}`)) {
        sessions.add(session);
      }
    }
    row.sessions = [...sessions].sort((a, b) => a.localeCompare(b));
    return row.sessions;
  };
  for (let index = 0; index < forest.length; index++) {
    walk(forest[index], null, `${index + 1}`);
  }
  return rows;
}

function HeaderCell({
  column,
  sort,
  onSort,
  filterActive,
  popoverOpen,
  onTogglePopover,
  popover,
}: {
  column: (typeof COLUMNS)[number];
  sort: SortState;
  onSort: (key: SortKey) => void;
  filterActive: boolean;
  popoverOpen: boolean;
  onTogglePopover: () => void;
  popover: ReactNode;
}) {
  const sorted =
    sort != null && column.sortKey != null && sort.key === column.sortKey
      ? sort.dir
      : null;
  const label = column.sortKey ? (
    <button
      type="button"
      onClick={() => onSort(column.sortKey!)}
      aria-label={`Sort by ${column.label}`}
      className="inline-flex items-center gap-1 rounded-md px-1 py-0.5 text-xs font-semibold uppercase tracking-wider text-hestia-text-muted transition hover:bg-hestia-text/5 hover:text-hestia-text"
    >
      {column.label}
      <span className="inline-block w-2.5 text-xs text-hestia-primary">
        {sorted === 1 ? "▲" : sorted === -1 ? "▼" : ""}
      </span>
    </button>
  ) : (
    <span className="px-1 py-0.5 text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
      {column.label}
    </span>
  );
  return (
    <div
      role="columnheader"
      className={`relative flex items-center gap-0.5 px-2.5 py-2 ${column.alignRight ? "justify-end" : ""}`}
    >
      {label}
      {column.filterKey && (
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
      {popover}
    </div>
  );
}

function AppendKnob({
  depth,
  label,
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
}: {
  depth: number;
  label: string;
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
          placeholder={label.replace("Add ", "Describe a ").concat("…")}
          error={error}
          pending={pending}
          onChange={onChange}
          onSubmit={onSubmit}
          onCancel={onCancel}
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
  open,
  childCount,
  evidenceMatch,
  onToggle,
  onOpen,
}: {
  row: Row;
  depth: number;
  zebra: boolean;
  context: boolean;
  filtering: boolean;
  open: boolean;
  /** Children this row still renders — knowledge is not among them, so it decides the caret. */
  childCount: number;
  /** The row only survived the search because of the knowledge it hides, which it says below. */
  evidenceMatch: boolean;
  onToggle: (id: number) => void;
  onOpen: (row: Row) => void;
}) {
  const meta = COMPETENCY_ROLE_META[row.role];
  const interactive = !context;
  const canToggle = childCount > 0 && !filtering;
  // Role-tinted rail beside the name, so the tier reads at a glance; knowledge is faded so the
  // capability tiers (skill / sub-skill) and gaps stand out.
  const railColor =
    row.role === "knowledge"
      ? `color-mix(in srgb, ${meta.color} 55%, transparent)`
      : meta.color;
  // A skill covers its children's sessions, and a document can be called anything, so the cell
  // shows one chip and counts the rest rather than guessing how much text will fit. The count is
  // expandable in place, so nothing here is reachable only by hovering.
  const [allSessions, setAllSessions] = useState(false);
  const shownSessions = allSessions ? row.sessions : row.sessions.slice(0, 1);
  return (
    <div
      role="row"
      data-goal-id={row.id}
      {...(interactive
        ? {
            tabIndex: 0,
            onClick: () => onOpen(row),
            onKeyDown: (e: ReactKeyboardEvent) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                onOpen(row);
              }
            },
          }
        : {})}
      className={`grid items-stretch border-b border-hestia-border/60 transition ${
        zebra ? "bg-hestia-text/3" : ""
      } ${
        context
          ? "opacity-45"
          : "cursor-pointer hover:bg-[color-mix(in_srgb,var(--hestia-primary)_7%,transparent)]"
      }`}
      style={{ gridTemplateColumns: GRID_COLS }}
    >
      <div role="gridcell" className="px-2.5 py-1.5">
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
          ) : (
            <span className="h-5 w-5 shrink-0" aria-hidden="true" />
          )}
          <span
            className={`pt-px text-sm leading-relaxed text-hestia-text ${
              row.role === "competency" ? "font-semibold" : ""
            }`}
          >
            <span className="mr-1 tabular-nums text-hestia-text-muted">{row.number}.</span>
            {displayedGoalLabel(row.goal)}
            {evidenceMatch && (
              <span className="ml-2 whitespace-nowrap text-xs text-hestia-text-muted">
                matched in its knowledge
              </span>
            )}
          </span>
        </div>
      </div>
      <div role="gridcell" className="px-2.5 py-1.5">
        <Pill label={meta.label} color={meta.color} />
      </div>
      <div role="gridcell" className="px-2.5 py-1.5">
        {row.goal.creationProvenance === "WIZARD_AI_SUBTREE" ? (
          <Pill label="AI-inferred" color="var(--hestia-danger)" />
        ) : row.goal.creationProvenance === "USER_CREATED" ? (
          <Pill label="Manual" color="var(--hestia-warning)" />
        ) : (
          row.goal.kind && (
            <Pill
              label={titleCase(row.goal.kind)}
              color="var(--hestia-text-muted)"
            />
          )
        )}
      </div>
      <div
        role="gridcell"
        className="py-1.5 pl-2.5 pr-4 text-right text-sm tabular-nums text-hestia-text-muted"
      >
        {row.childCount > 0 ? row.childCount : "—"}
      </div>
      <div
        role="gridcell"
        className="min-w-0 px-2.5 py-1.5 text-xs text-hestia-text-muted"
      >
        {row.sessions.length === 0 ? (
          "—"
        ) : (
          <div
            className={`flex min-w-0 items-center gap-1 ${allSessions ? "flex-wrap" : ""}`}
          >
            {shownSessions.map((session) => (
              <span
                key={session}
                title={session}
                className="max-w-full truncate rounded-md border border-hestia-border/60 bg-hestia-text/3 px-1.5 py-0.5"
              >
                {session}
              </span>
            ))}
            {row.sessions.length > 1 && (
              <button
                type="button"
                aria-expanded={allSessions}
                aria-label={
                  allSessions
                    ? "Show fewer sessions"
                    : `Show ${row.sessions.length - 1} more sessions`
                }
                title={allSessions ? undefined : row.sessions.slice(1).join("\n")}
                onClick={(e) => {
                  e.stopPropagation();
                  setAllSessions((open) => !open);
                }}
                // The row itself opens the goal on click and on Enter/Space, so this control has to
                // keep both to itself.
                onKeyDown={(e) => e.stopPropagation()}
                className="shrink-0 rounded-md border border-[color-mix(in_srgb,var(--hestia-primary)_30%,transparent)] bg-hestia-primary-muted px-1.5 py-0.5 font-medium tabular-nums text-hestia-primary transition hover:bg-[color-mix(in_srgb,var(--hestia-primary)_28%,transparent)]"
              >
                {allSessions ? "−" : `+${row.sessions.length - 1}`}
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/** Small tinted attribute pill, coloured via a HESTIA CSS variable so it tracks the theme. */
function Pill({ label, color }: { label: string; color: string }) {
  return (
    <span
      className="inline-flex items-center whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold uppercase tracking-wide"
      style={{
        color,
        backgroundColor: `color-mix(in srgb, ${color} 15%, transparent)`,
      }}
    >
      {label}
    </span>
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
      className="h-3.5 w-3.5"
    >
      {collapse ? (
        <path d="M6 8.5l4-3.5 4 3.5M6 11.5l4 3.5 4-3.5" />
      ) : (
        <path d="M6 5.5l4 3.5 4-3.5M6 14.5l4-3.5 4 3.5" />
      )}
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
