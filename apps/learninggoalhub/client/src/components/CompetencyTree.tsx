import {
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactElement,
  type ReactNode,
} from "react";
import type { LearningGoal } from "../api/client.ts";
import CompetencyGoalModal from "./CompetencyGoalModal.tsx";
import FilterPopover from "./FilterPopover.tsx";
import {
  COMPETENCY_ROLE_META,
  buildCompetencyForest,
  titleCase,
  type CompetencyNode,
  type CompetencyRole,
} from "../lib/goals.ts";

/**
 * The competency tree as an Excel-like tree-grid: the Skill → Sub-skill → Knowledge hierarchy
 * (the same forest the map view shows) lives in the first column with expand/collapse carets,
 * while every goal attribute becomes a proper column with a funnel filter (multi-select
 * checkboxes) and hierarchy-preserving sorting (siblings are sorted within their parent).
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
  goal: LearningGoal;
  role: CompetencyRole;
  childCount: number;
};

type FilterKey = "role" | "bloom" | "solo";
type SortKey = "text" | "bloom" | "solo" | "items";
type SortState = { key: SortKey; dir: 1 | -1 } | null;

// Shared grid template so the sticky header row and every body row line their columns up: a
// flexible learning-goal column, then fixed attribute columns. Kept in one place so header and
// rows can never drift apart.
const GRID_COLS = "minmax(240px,1fr) 108px 128px 138px 72px";

const BLOOM_ORDER = [
  "REMEMBER",
  "UNDERSTAND",
  "APPLY",
  "ANALYZE",
  "EVALUATE",
  "CREATE",
];
const SOLO_ORDER = [
  "PRESTRUCTURAL",
  "UNISTRUCTURAL",
  "MULTISTRUCTURAL",
  "RELATIONAL",
  "EXTENDED_ABSTRACT",
];
const ROLE_ORDER: CompetencyRole[] = [
  "competency",
  "sub-skill",
  "knowledge",
  "gap",
];

const prefersReducedMotion = () =>
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/** The filterable value of a row in one column. */
function valueOf(row: Row, key: FilterKey): string {
  switch (key) {
    case "role":
      return row.role;
    case "bloom":
      return row.goal.bloomLevel ?? "";
    case "solo":
      return row.goal.soloLevel ?? "";
  }
}

/** Human label for a raw column value (role names, title-cased enums). */
function displayValue(key: FilterKey, value: string): string {
  if (key === "role") return COMPETENCY_ROLE_META[value as CompetencyRole].label;
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
  { key: "bloom", label: "Bloom", sortKey: "bloom", filterKey: "bloom" },
  { key: "solo", label: "SOLO", sortKey: "solo", filterKey: "solo" },
  { key: "items", label: "Items", sortKey: "items", alignRight: true },
];

export default function CompetencyTree({
  goals,
}: {
  goals: LearningGoal[];
}) {
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

  const [expanded, setExpanded] = useState<Set<number>>(
    () => new Set(forest.map((n) => n.goal.id!).filter((id) => id != null)),
  );
  const [search, setSearch] = useState("");
  const [filters, setFilters] = useState<Record<FilterKey, Set<string>>>({
    role: new Set(),
    bloom: new Set(),
    solo: new Set(),
  });
  const [sort, setSort] = useState<SortState>(null);
  const [openFilter, setOpenFilter] = useState<FilterKey | null>(null);
  // Clicking a row opens the same classification overlay the map view uses. The modal only reads
  // the node's goal and role, so a row's flat data is enough to build one.
  const [detail, setDetail] = useState<{
    goal: LearningGoal;
    role: CompetencyRole;
  } | null>(null);
  const openDetail = (row: Row) =>
    setDetail({ goal: row.goal, role: row.role });

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
      if (needle && !(row.goal.text ?? "").toLowerCase().includes(needle))
        return false;
      for (const key of Object.keys(filters) as FilterKey[]) {
        const set = filters[key];
        if (set.size > 0 && !set.has(valueOf(row, key))) return false;
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
    return { matchIds: matches, contextIds: context };
  }, [rows, byId, search, filters, filtering]);

  // Only offer filter values that actually occur, in taxonomy (not alphabetical) order.
  const filterOptions = useMemo(() => {
    const present = (key: FilterKey) =>
      new Set(rows.map((r) => valueOf(r, key)).filter((v) => v !== ""));
    const ordered = (order: string[], values: Set<string>) =>
      order.filter((v) => values.has(v));
    return {
      role: ordered(ROLE_ORDER, present("role")),
      bloom: ordered(BLOOM_ORDER, present("bloom")),
      solo: ordered(SOLO_ORDER, present("solo")),
    };
  }, [rows]);

  const sortSiblings = (siblings: Row[]): Row[] => {
    if (!sort) return siblings;
    const { key, dir } = sort;
    const rank = (row: Row): string | number => {
      switch (key) {
        case "text":
          return row.goal.text ?? "";
        case "bloom":
          return BLOOM_ORDER.indexOf(row.goal.bloomLevel ?? "");
        case "solo":
          return SOLO_ORDER.indexOf(row.goal.soloLevel ?? "");
        case "items":
          return row.childCount;
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
  const prevRects = useRef<Map<number, DOMRect>>(new Map());
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
    const prev = prevRects.current;
    const intent = enterIntent.current;
    const next = new Map<number, DOMRect>();
    let enterIndex = 0;
    container
      .querySelectorAll<HTMLElement>("[data-goal-id]")
      .forEach((el) => {
        const id = Number(el.dataset.goalId);
        const to = el.getBoundingClientRect();
        next.set(id, to);
        if (reduce || firstLayout.current) return;
        // Clear any leftover entrance state from an earlier cascade before deciding afresh.
        el.classList.remove("tree-row-in");
        el.style.animationDelay = "";
        const from = prev.get(id);
        if (from) {
          const dy = from.top - to.top;
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
    prevRects.current = next;
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
    setFilters({ role: new Set(), bloom: new Set(), solo: new Set() });
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
    () => rows.filter((r) => r.childCount > 0).map((r) => r.id),
    [rows],
  );
  const allOpen =
    parentIds.length > 0 && parentIds.every((id) => expanded.has(id));

  if (forest.length === 0) {
    return (
      <p className="rounded-xl border border-dashed border-hestia-border p-8 text-center text-sm text-hestia-text-muted">
        No competency tree for this course yet. Re-run the extraction to
        synthesise terminal competencies, sub-skills and knowledge.
      </p>
    );
  }

  // Depth-first walk producing the visible rows: expansion state applies while browsing, filters
  // force the full path to every match open.
  const bodyRows: ReactElement[] = [];
  const walk = (siblings: Row[], depth: number) => {
    for (const row of sortSiblings(siblings)) {
      const isMatch = !filtering || matchIds!.has(row.id);
      const isContext = filtering && contextIds.has(row.id);
      if (filtering && !isMatch && !isContext) continue;
      bodyRows.push(
        <GridRow
          key={row.id}
          row={row}
          depth={depth}
          zebra={bodyRows.length % 2 === 1}
          context={isContext}
          filtering={filtering}
          open={expanded.has(row.id)}
          onToggle={onToggle}
          onOpen={openDetail}
        />,
      );
      if (filtering || expanded.has(row.id))
        walk(childrenOf.get(row.id) ?? [], depth + 1);
    }
  };
  walk(childrenOf.get(null) ?? [], 0);

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
        {!filtering && (
          <button
            type="button"
            onClick={() => {
              if (allOpen) {
                setExpanded(new Set());
              } else {
                enterIntent.current = "all";
                setExpanded(new Set(parentIds));
              }
            }}
            className="text-sm font-medium text-hestia-primary transition hover:text-hestia-primary-hover"
          >
            {allOpen ? "Collapse all" : "Expand all"}
          </button>
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
        <div className="max-h-[72vh] overflow-auto">
          <div role="table" aria-label="Competency tree" className="min-w-[680px]">
            <div
              role="row"
              className="sticky top-0 z-10 grid border-b border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_4%,var(--hestia-surface))]"
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
                <div className="p-9 text-center text-sm text-hestia-text-muted">
                  No goals match the current filters.
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
      <CompetencyGoalModal
        goal={detail?.goal ?? null}
        role={detail?.role}
        onClose={() => setDetail(null)}
      />
    </div>
  );
}

/** Flattens the forest depth-first into rows that keep the structure via parent ids. */
function flattenForest(forest: CompetencyNode[]): Row[] {
  const rows: Row[] = [];
  const walk = (node: CompetencyNode, parent: number | null) => {
    if (node.goal.id == null) return;
    rows.push({
      id: node.goal.id,
      parent,
      goal: node.goal,
      role: node.role,
      childCount: node.children.length,
    });
    for (const child of node.children) walk(child, node.goal.id);
  };
  for (const node of forest) walk(node, null);
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
  return (
    <div
      role="columnheader"
      className={`relative flex items-center gap-0.5 px-2.5 py-2 ${column.alignRight ? "justify-end" : ""}`}
    >
      {column.sortKey ? (
        <button
          type="button"
          onClick={() => onSort(column.sortKey!)}
          aria-label={`Sort by ${column.label}`}
          className="inline-flex items-center gap-1 rounded-md px-1 py-0.5 text-[0.7rem] font-semibold uppercase tracking-wider text-hestia-text-muted transition hover:bg-hestia-text/5 hover:text-hestia-text"
        >
          {column.label}
          <span className="inline-block w-2.5 text-[0.6rem] text-hestia-primary">
            {sorted === 1 ? "▲" : sorted === -1 ? "▼" : ""}
          </span>
        </button>
      ) : (
        <span className="px-1 py-0.5 text-[0.7rem] font-semibold uppercase tracking-wider text-hestia-text-muted">
          {column.label}
        </span>
      )}
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

function GridRow({
  row,
  depth,
  zebra,
  context,
  filtering,
  open,
  onToggle,
  onOpen,
}: {
  row: Row;
  depth: number;
  zebra: boolean;
  context: boolean;
  filtering: boolean;
  open: boolean;
  onToggle: (id: number) => void;
  onOpen: (row: Row) => void;
}) {
  const meta = COMPETENCY_ROLE_META[row.role];
  const interactive = !context;
  const canToggle = row.childCount > 0 && !filtering;
  // Role-tinted rail beside the name, so the tier reads at a glance; knowledge is faded so the
  // capability tiers (skill / sub-skill) and gaps stand out.
  const railColor =
    row.role === "knowledge"
      ? `color-mix(in srgb, ${meta.color} 55%, transparent)`
      : meta.color;
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
              className="flex h-5 w-5 shrink-0 items-center justify-center rounded text-hestia-text-muted transition hover:bg-hestia-primary-muted hover:text-hestia-text"
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
            {row.goal.text}
          </span>
        </div>
      </div>
      <div role="gridcell" className="px-2.5 py-1.5">
        <Pill label={meta.label} color={meta.color} />
      </div>
      <div role="gridcell" className="px-2.5 py-1.5">
        {row.goal.bloomLevel && (
          <Pill
            label={titleCase(row.goal.bloomLevel)}
            color="var(--hestia-text-muted)"
          />
        )}
      </div>
      <div role="gridcell" className="px-2.5 py-1.5">
        {row.goal.soloLevel && (
          <Pill
            label={titleCase(row.goal.soloLevel)}
            color="var(--hestia-text-muted)"
          />
        )}
      </div>
      <div
        role="gridcell"
        className="py-1.5 pl-2.5 pr-4 text-right text-sm tabular-nums text-hestia-text-muted"
      >
        {row.childCount > 0 ? row.childCount : "—"}
      </div>
    </div>
  );
}

/** Small tinted attribute pill, coloured via a HESTIA CSS variable so it tracks the theme. */
function Pill({ label, color }: { label: string; color: string }) {
  return (
    <span
      className="inline-flex items-center whitespace-nowrap rounded-full px-2 py-0.5 text-[0.65rem] font-semibold uppercase tracking-wide"
      style={{
        color,
        backgroundColor: `color-mix(in srgb, ${color} 15%, transparent)`,
      }}
    >
      {label}
    </span>
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
