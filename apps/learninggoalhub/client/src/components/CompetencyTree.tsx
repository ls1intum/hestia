import {
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactElement,
} from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, API_PREFIX, type LearningGoal } from "../api/client.ts";
import TopicSearchDialog from "./TopicSearchDialog.tsx";
import { createTopic } from "../lib/createTopic.ts";
import Button from "./Button.tsx";
import ErrorBoundary from "./ErrorBoundary.tsx";
import FilterPopover from "./FilterPopover.tsx";
import TopicMap, { type MapCreation } from "./TopicMap.tsx";
import {
  buildCompetencyForest,
  coverageCounts,
  coverageOf,
  levelFlags,
  type CompetencyRole,
} from "../lib/goals.ts";
import {
  BLOOM_ORDER,
  CHILD_APPEND,
  SOLO_ORDER,
  NO_IDS,
  KIND_ORDER,
  COVERAGE_ORDER,
  ROLE_ORDER,
  prefersReducedMotion,
  valuesOf,
  displayValue,
  FILTER_LABELS,
  flattenForest,
  type Row,
  type FilterKey,
  type LevelScale,
  type GoalChanges,
  type Layout,
  type CreationTier,
  type CreationState,
  type AttributeKey,
  type ColumnKey,
  type ColumnPrefs,
} from "./competencyTree/model.ts";
import {
  COLUMN_PREFS_KEY,
  MIN_COLUMN_WIDTH,
  MIN_GOAL_COLUMN_WIDTH,
  COLUMNS,
  COLUMN_BY_KEY,
  DEFAULT_COLUMN_PREFS,
  loadColumnPrefs,
  gridTemplate,
} from "./competencyTree/columns.ts";
import { LooseGroupRow, DiagramAttributes } from "./competencyTree/DiagramRows.tsx";
import { HeaderCell } from "./competencyTree/HeaderCell.tsx";
import { DisplayMenu, LayoutSwitch } from "./competencyTree/Toolbar.tsx";
import { TierGuideMenu, TierGuideStrip } from "./competencyTree/TierGuide.tsx";
import { FoldIcon, CrossIcon } from "./competencyTree/icons.tsx";
import { AppendKnob, GridRow, SessionName } from "./competencyTree/GridRow.tsx";

// Lazily loaded so the heavy pdf.js bundle only ships once a source is opened, not on first paint.
const SourcePdfPane = lazy(() => import("./SourcePdfPane.tsx"));
/** Set once a reader dismissed a course's first-visit hint on how to read the table. */
const tierGuideSeenKey = (courseId: number) => `learninggoalhub.tierGuide.seen.${courseId}`;

// The grid's scroll area: it reaches the bottom of the window bar this gap, and never shrinks
// below a few rows on a short window — there the page scrolls instead.
const SCROLLER_BOTTOM_GAP = 32;
const MIN_SCROLLER_HEIGHT = 320;

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
    /** Called when the save fails, so the edit shown ahead of it can be taken back. */
    onFailed: () => void,
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
    // A failed save leaves the list unchanged, so the edit would otherwise stay on screen as if saved.
    const rollback = () =>
      setPending((prev) => {
        const current = prev.changes.get(goalId);
        if (!current) return prev;
        const kept = Object.fromEntries(
          Object.entries(current).filter(([key]) => !(key in changes)),
        ) as GoalChanges;
        const next = new Map(prev.changes);
        if (Object.keys(kept).length === 0) next.delete(goalId);
        else next.set(goalId, kept);
        return { ...prev, changes: next };
      });
    onUpdate(goalId, changes, rollback);
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
  // The strip explaining the tiers shows on a reader's first visit to a course; once dismissed it
  // folds into the toolbar's "How to read this" button, which flashes briefly to show where it went.
  const [tierGuideOpen, setTierGuideOpen] = useState(false);
  const [tierGuideSeen, setTierGuideSeen] = useState(() => {
    try {
      return localStorage.getItem(tierGuideSeenKey(courseId)) != null;
    } catch {
      return false;
    }
  });
  const [tierGuideFlash, setTierGuideFlash] = useState(false);
  const dismissTierGuide = () => {
    setTierGuideSeen(true);
    setTierGuideFlash(true);
    try {
      localStorage.setItem(tierGuideSeenKey(courseId), "1");
    } catch {
      // Without storage the strip simply returns on the next visit.
    }
  };
  useEffect(() => {
    if (!tierGuideFlash) return;
    const timer = window.setTimeout(() => setTierGuideFlash(false), 1600);
    return () => window.clearTimeout(timer);
  }, [tierGuideFlash]);
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
      displayMenuOpen ||
      tierGuideOpen
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
  }, [sourceGoalId, openFilter, openLevel, editingId, creation, displayMenuOpen, tierGuideOpen]);

  const filtering =
    search.trim() !== "" || Object.values(filters).some((s) => s.size > 0);

  // Whether a row survives the search and every filter, optionally ignoring one key: the popover's
  // per-value counts ask what one column's filter would leave once the others have had their say.
  const matchesRow = useCallback(
    (row: Row, ignore: FilterKey | null = null): boolean => {
      const needle = search.trim().toLowerCase();
      if (
        needle &&
        ![row.goal.shortLabel, row.goal.text].some((value) =>
          (value ?? "").toLowerCase().includes(needle),
        )
      )
        return false;
      for (const key of Object.keys(filters) as FilterKey[]) {
        if (key === ignore) continue;
        const set = filters[key];
        if (set.size > 0 && !valuesOf(row, key).some((v) => set.has(v)))
          return false;
      }
      return true;
    },
    [search, filters],
  );

  // Rows surviving the filters, plus their non-matching ancestors as dimmed context.
  const { matchIds, contextIds } = useMemo(() => {
    if (!filtering)
      return {
        matchIds: null as Set<number> | null,
        contextIds: new Set<number>(),
      };
    const matches = new Set(rows.filter((row) => matchesRow(row)).map((r) => r.id));
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
  }, [rows, byId, matchesRow, filtering]);

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
  // The goals under a topic that sit in no skill, gathered under one "Additional sub-skills"
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

  // How many rows each filter value would leave, counted against the search and the other columns'
  // filters but not its own — so the numbers in one popover stay comparable as you tick values.
  const filterCounts = useMemo(() => {
    const counts = {} as Record<FilterKey, Map<string, number>>;
    for (const key of Object.keys(filters) as FilterKey[]) {
      const map = new Map<string, number>();
      for (const row of rows) {
        if (!matchesRow(row, key)) continue;
        for (const value of valuesOf(row, key))
          if (value !== "") map.set(value, (map.get(value) ?? 0) + 1);
      }
      counts[key] = map;
    }
    return counts;
  }, [rows, filters, matchesRow]);

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

  // The grid ends at the bottom of the window, whatever stands above it — page title, toolbar, the
  // tier strip, a row of filter chips. A fixed slice of the viewport had to leave room for the
  // tallest of those, so it wasted rows whenever they were absent.
  const [scrollerHeight, setScrollerHeight] = useState<number>();
  useLayoutEffect(() => {
    const scroller = scrollerRef.current;
    if (!scroller) return;
    // Measured against the document, not the viewport, so scrolling the page never resizes the grid.
    const measure = () =>
      setScrollerHeight(
        Math.max(
          MIN_SCROLLER_HEIGHT,
          window.innerHeight -
            (scroller.getBoundingClientRect().top + window.scrollY) -
            SCROLLER_BOTTOM_GAP,
        ),
      );
    measure();
    // Anything appearing above the grid moves it down; the body's height change catches all of it.
    const observer = new ResizeObserver(measure);
    observer.observe(document.body);
    window.addEventListener("resize", measure);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", measure);
    };
  }, []);

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

  // Switching to the diagram opens its first topic: a list of closed topic rows shows none of what
  // the layout is for. A topic closed by hand stays closed — only an empty layout opens itself.
  const changeLayout = (next: Layout) => {
    if (next === "diagram" && openTopicId == null)
      setOpenTopicId(forest[0]?.goal.id ?? null);
    setLayout(next);
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
    // A goal hanging directly under a topic sits in the "Additional sub-skills" group, one
    // step further in, level with the sub-skills under a skill. Its knowledge follows it in.
    const rowDepth = row.role !== "capability" && parentRole === "topic" ? depth + 1 : depth;
    // The "+" lives in the table only: the map adds in the map, and a filtered list has no place
    // for the new row to appear.
    const childAppend = CHILD_APPEND[row.role];
    const canAddChild =
      layout === "table" &&
      !filtering &&
      childAppend != null;
    // A topic and what hangs off it read as one block in both layouts, so every topic after the
    // first gets a little air above it. A filtered list is one flat run of matches and keeps none.
    const spacedTop = !filtering && row.role === "topic" && bodyRows.length > 0;
    bodyRows.push(
      <GridRow
        key={row.id}
        row={row}
        depth={rowDepth}
        spacedTop={spacedTop}
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
        members={
          row.role === "capability"
            ? (childrenOf.get(row.id) ?? []).filter((child) => child.role === "skill")
            : undefined
        }
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
                    members={
                      nodeRow.role === "capability"
                        ? (childrenOf.get(nodeRow.id) ?? []).filter((child) => child.role === "skill")
                        : undefined
                    }
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


  const matchCount = matchIds?.size ?? rows.length;
  // Each chip carries what it alone leaves, counted the way the popover counts a value: against the
  // search and every other chip, but not against its own column. The search chip's own count is the
  // whole result, since nothing else narrows it further.
  const activeChips: {
    label: string;
    value: string;
    count: number;
    onRemove: () => void;
  }[] = [];
  if (search.trim())
    activeChips.push({
      label: "Search",
      value: `“${search.trim()}”`,
      count: matchCount,
      onRemove: () => setSearch(""),
    });
  for (const column of COLUMNS) {
    for (const key of column.filterKeys ?? []) {
      for (const value of filters[key]) {
        activeChips.push({
          label: FILTER_LABELS[key],
          value: displayValue(key, value),
          count: filterCounts[key].get(value) ?? 0,
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
        <LayoutSwitch layout={layout} onChange={changeLayout} />
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
        <TierGuideMenu
          open={tierGuideOpen}
          flash={tierGuideFlash}
          onToggleOpen={() => setTierGuideOpen((prev) => !prev)}
          onClose={() => setTierGuideOpen(false)}
        />
      </div>

      {!tierGuideSeen && (
        <TierGuideStrip
          onOpenGuide={() => {
            dismissTierGuide();
            setTierGuideOpen(true);
          }}
          onDismiss={dismissTierGuide}
        />
      )}

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
              <span className="border-l border-[color-mix(in_srgb,var(--hestia-primary)_30%,transparent)] pl-1.5 tabular-nums text-hestia-text-muted">
                {chip.count === 1 ? "1 goal" : `${chip.count} goals`}
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
          {/* What the chips leave together, which only says something a single chip's own count
              does not once a second chip narrows it. */}
          {activeChips.length > 1 && (
            <span className="text-xs text-hestia-text-muted" aria-live="polite">
              {matchCount === 1 ? "1 goal" : `${matchCount} goals`} of{" "}
              {rows.length}
            </span>
          )}
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
        <div
          ref={scrollerRef}
          className="relative overflow-auto pb-4"
          style={{ maxHeight: scrollerHeight }}
        >
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
                          count: (v) => filterCounts[key].get(v) ?? 0,
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
