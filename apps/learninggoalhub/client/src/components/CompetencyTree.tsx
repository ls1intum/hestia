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
  type ReactNode,
} from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, API_PREFIX } from "../api/client.ts";
import type { LearningGoal } from "../api/client.ts";
import CompetencyGoalModal from "./CompetencyGoalModal.tsx";
import CapabilityModal from "./CapabilityModal.tsx";
import CompetencyCreationField from "./CompetencyCreationField.tsx";
import TopicSearchDialog from "./TopicSearchDialog.tsx";
import { createTopic } from "../lib/createTopic.ts";
import AnchoredPopover from "./AnchoredPopover.tsx";
import Button from "./Button.tsx";
import ErrorBoundary from "./ErrorBoundary.tsx";
import FilterPopover from "./FilterPopover.tsx";
import CoverageBadge from "./CoverageBadge.tsx";
import TopicMap, { type MapCreation } from "./TopicMap.tsx";
// Lazily loaded so the heavy pdf.js bundle only ships once a source is opened, not on first paint.
const SourcePdfPane = lazy(() => import("./SourcePdfPane.tsx"));
import {
  BLOOM_DESC,
  COMPETENCY_ROLE_META,
  SOLO_DESC,
  buildCompetencyForest,
  childGoalsOf,
  coverageCounts,
  coverageOf,
  generatedChildCount,
  levelFlags,
  supportingOutcomesOf,
  titleCase,
  type CompetencyNode,
  type CompetencyRole,
  type CoverageCounts,
} from "../lib/goals.ts";

/**
 * The competency tree as an Excel-like grid. Every goal attribute is a proper column with a funnel
 * filter (multi-select checkboxes) and hierarchy-preserving sorting (siblings are sorted within
 * their parent). The grid browses the tree in one of two layouts, switched in its toolbar:
 *
 * - `table`: the Topic → Capability → Skill hierarchy lives in the first column with
 *   expand/collapse carets, and branches unfold as rows in place.
 * - `map`: the grid lists topics only, and clicking a topic row opens that topic's map inline
 *   beneath it (see `TopicMap`). The open row stays pinned while the map is read.
 *
 * Filter semantics: while a filter or search is active the grid becomes a list of matches. The
 * matching capabilities, skills and knowledge appear as rows in their tree position, and ancestors
 * of a match that don't match themselves are shown dimmed as context-only rows. Rows in that list
 * open the goal detail modal, in both layouts; clearing the filters brings back the chosen layout.
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
type SortKey = "text" | "bloom" | "solo" | "source";
type LevelScale = "bloom" | "solo";
type GoalChanges = {
  text?: string;
  bloomLevel?: LearningGoal["bloomLevel"];
  soloLevel?: LearningGoal["soloLevel"];
};
type SortState = { key: SortKey; dir: 1 | -1 } | null;
type Layout = "table" | "map";
type CreationTier = 1 | 2 | 3 | 4;
type CreationState = {
  key: string;
  tier: CreationTier;
  parentGoalId: number | null;
  text: string;
  /** A topic created together with AI-written skills beneath it, instead of on its own. */
  generate?: boolean;
};

// Shared grid template so the sticky header row and every body row line their columns up: a
// flexible learning-goal column, then fixed attribute columns. Kept in one place so header and
// rows can never drift apart.
// Source carries full session titles, so it takes a share of the free space rather than a fixed
// width — otherwise the goal column swallows everything.
const GRID_COLS =
  "minmax(240px,1fr) 100px 96px 108px 132px minmax(180px,0.55fr)";

/** Maps a title-cased ladder term back to its API enum value ("Extended Abstract" → "EXTENDED_ABSTRACT"). */
const toEnum = (term: string) => term.toUpperCase().replace(/ /g, "_");

// Each taxonomy's ladder is the insertion order of its description map, the same order the goal
// modal's dot scales use.
const LEVEL_SCALES = {
  bloom: { label: "Bloom", desc: BLOOM_DESC, dotClass: "bg-hestia-accent" },
  solo: { label: "SOLO", desc: SOLO_DESC, dotClass: "bg-hestia-primary" },
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
/** The server caps a topic at this many direct children. */
const MAX_TOPIC_CHILDREN = 5;
const SOLO_ORDER = Object.keys(SOLO_DESC).map(toEnum);

// "AI_INFERRED" is a synthetic kind value derived from a goal's WIZARD_AI_SUBTREE provenance, so the
// Kind column and its filter surface AI-generated goals without a separate GoalKind enum on the server.
const AI_INFERRED_KIND = "AI_INFERRED";
const MANUAL_KIND = "MANUAL";
const KIND_ORDER = ["EXPLICIT", "IMPLICIT", AI_INFERRED_KIND, MANUAL_KIND];
// Coverage filter values: a sub-skill only a lecture teaches is not practised, one only an exercise
// asks for is not introduced. Sub-skills covered by both, or whose documents have no kind, match
// neither.
const NOT_PRACTISED = "NOT_PRACTISED";
const NOT_INTRODUCED = "NOT_INTRODUCED";
const COVERAGE_ORDER = [NOT_PRACTISED, NOT_INTRODUCED];
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
    case "session":
      return [row.session];
    case "coverage": {
      if (row.role !== "skill") return [""];
      const coverage = coverageOf(row.goal);
      return [coverage === "lecture" ? NOT_PRACTISED : coverage === "exercise" ? NOT_INTRODUCED : ""];
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

/** The short label by default; `full` asks for the complete wording instead. */
function displayedGoalLabel(goal: LearningGoal, full = false): string {
  return (full ? goal.text : goal.shortLabel) ?? goal.shortLabel ?? goal.text ?? "";
}

/** Human label for a raw column value (role names, title-cased enums). */
function displayValue(key: FilterKey, value: string): string {
  if (key === "role")
    return COMPETENCY_ROLE_META[value as CompetencyRole].label;
  if (key === "session" || key === "document") return value || "—";
  if (value === NOT_PRACTISED) return "Not practised (lecture only)";
  if (value === NOT_INTRODUCED) return "Not introduced (exercise only)";
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
  key: string;
  label: string;
  sortKey?: SortKey;
  /** The attributes the column's funnel filters on; Source offers both its document and session. */
  filterKeys?: FilterKey[];
}[] = [
  { key: "text", label: "Learning goal", sortKey: "text" },
  { key: "role", label: "Tier", filterKeys: ["role"] },
  { key: "kind", label: "Kind", filterKeys: ["kind"] },
  { key: "bloom", label: "Bloom", sortKey: "bloom", filterKeys: ["bloom"] },
  { key: "solo", label: "SOLO", sortKey: "solo", filterKeys: ["solo"] },
  {
    key: "source",
    label: "Source",
    sortKey: "source",
    filterKeys: ["document", "session", "coverage"],
  },
];

export default function CompetencyTree({
  courseId,
  goals,
  onUpdate,
  onDelete,
  onEdit,
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
  /** The edit pencil on a map box. */
  onEdit: (goal: LearningGoal) => void;
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
  const [sort, setSort] = useState<SortState>(null);
  // Rows show short labels by default; the toolbar switch swaps in every goal's full wording.
  const [fullWording, setFullWording] = useState(false);
  // The column (by key) whose filter popover is open.
  const [openFilter, setOpenFilter] = useState<string | null>(null);
  // Clicking a row opens the same classification overlay the map view uses. The modal only reads
  // the node's goal and role, so a row's flat data is enough to build one.
  const [detail, setDetail] = useState<{
    goal: LearningGoal;
    role: CompetencyRole;
  } | null>(null);
  // The goal the modal was drilled into from, so a knowledge goal opened out of the evidence list
  // can hand the reader back to its skill instead of dropping them out of the modal entirely.
  const [detailParent, setDetailParent] = useState<{
    goal: LearningGoal;
    role: CompetencyRole;
  } | null>(null);
  const openDetail = (row: Row) => {
    setDetailParent(null);
    setDetail({ goal: row.goal, role: row.role });
  };
  // A capability's detail opens its skills and their knowledge by their real role, and keeps itself
  // as the way back.
  const openFromCapability = (goal: LearningGoal, role: CompetencyRole) => {
    setDetailParent(detail);
    setDetail({ goal, role });
  };
  const onOpenGoal = (goal: LearningGoal) => {
    setDetailParent(detail);
    setDetail({ goal, role: "knowledge" });
  };
  const closeDetail = () => {
    setDetail(null);
    setDetailParent(null);
  };

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
  // Escape closes the panel, unless a modal, popover or rename above it owns the key.
  useEffect(() => {
    if (
      sourceGoalId == null ||
      detail != null ||
      openFilter != null ||
      openLevel != null ||
      editingId != null ||
      creation != null
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
  }, [sourceGoalId, detail, openFilter, openLevel, editingId, creation]);

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

  const sortSiblings = (siblings: Row[]): Row[] => {
    if (!sort) return siblings;
    const { key, dir } = sort;
    const rank = (row: Row): string | number => {
      switch (key) {
        case "text":
          return displayedGoalLabel(row.goal, fullWording);
        // Unset levels rank below the lowest one.
        case "bloom":
          return BLOOM_ORDER.indexOf(row.goal.bloomLevel ?? "");
        case "solo":
          return SOLO_ORDER.indexOf(row.goal.soloLevel ?? "");
        // By document, then by page within it, which is the order the material is read in.
        case "source":
          return `${documentOf(row)} ${String(row.goal.sources?.[0]?.page ?? 0).padStart(6, "0")}`;
      }
    };
    return [...siblings].sort((a, b) => {
      const va = rank(a);
      const vb = rank(b);
      return (va < vb ? -1 : va > vb ? 1 : 0) * dir;
    });
  };

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
  // opened, or "all" for expand-all. Empty for every other change (filter / search / sort / map),
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

  const onToggle = (id: number) =>
    expanded.has(id) ? collapse(id) : expand(id);

  const parentIds = useMemo(
    () => rows.filter((r) => (visibleChildCount.get(r.id) ?? 0) > 0).map((r) => r.id),
    [rows, visibleChildCount],
  );
  const allOpen =
    parentIds.length > 0 && parentIds.every((id) => expanded.has(id));

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

  const cycleSort = (key: SortKey) =>
    setSort((prev) =>
      prev?.key !== key
        ? { key, dir: 1 }
        : prev.dir === 1
          ? { key, dir: -1 }
          : null,
    );

  if (!hasTree) {
    return (
      <p className="mx-auto w-full max-w-5xl rounded-xl border border-dashed border-hestia-border p-8 text-center text-sm text-hestia-text-muted">
        No competency tree was created during extraction for this course.
      </p>
    );
  }

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
    const ordered = sortSiblings(siblings);
    for (let i = 0; i < ordered.length; i++) {
      const row = ordered[i];
      const isMatch = !filtering || matchIds!.has(row.id);
      const isContext = filtering && contextIds.has(row.id);
      if (filtering && !isMatch && !isContext) continue;
      const mapOpen = layout === "map" && !filtering && openTopicId === row.id;
      // A sub-skill hanging directly under a topic sits one step further in, level with the
      // sub-skills under a skill, so the indentation reads as the tier rather than the tree depth.
      // Its knowledge follows it in.
      const rowDepth = row.role === "skill" && parentRole === "topic" ? depth + 1 : depth;
      // The "+" lives in the table only: the map adds in the map, and a filtered list has no place
      // for the new row to appear.
      const childAppend = CHILD_APPEND[row.role];
      const canAddChild =
        layout === "table" &&
        !filtering &&
        childAppend != null &&
        (row.role !== "topic" ||
          (childrenOf.get(row.id)?.length ?? 0) < MAX_TOPIC_CHILDREN);
      bodyRows.push(
        <GridRow
          key={row.id}
          row={row}
          depth={rowDepth}
          zebra={rowIndex++ % 2 === 1}
          context={isContext}
          filtering={filtering}
          layout={layout}
          open={expanded.has(row.id)}
          childCount={visibleChildCount.get(row.id) ?? 0}
          mapOpen={mapOpen}
          stickyTop={headerHeight}
          onToggle={onToggle}
          onToggleMap={toggleMap}
          onOpen={openDetail}
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
            <div role="gridcell" aria-colspan={COLUMNS.length}>
              <TopicMap
                topic={topicNode}
                sequence={row.number}
                onOpenDetail={(node) => {
                  setDetailParent(null);
                  setDetail({ goal: node.goal, role: node.role });
                }}
                onEdit={onEdit}
                onDelete={onDelete}
                onClose={() => setOpenTopicId(null)}
                creation={mapCreation}
                suspendEscape={
                  detail != null ||
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
        filtering ||
        (layout === "table" &&
          (expanded.has(row.id) ||
            ((visibleChildCount.get(row.id) ?? 0) === 0 && row.role === "topic")))
      )
        walk(
          childrenOf.get(row.id) ?? [],
          rowDepth + 1,
          row.id,
          row.role,
          trailing && i === ordered.length - 1,
        );
    }
    // "Add topic" always closes the grid and "Add skill" closes each topic with room left; the
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
        depth === 0 ||
        (parentRole === "topic" && siblings.length < MAX_TOPIC_CHILDREN);
      if (append && (appendActive || resting)) {
        bodyRows.push(
          <AppendKnob
            key={`append-${appendKey}`}
            depth={depth}
            label={append.label}
            placeholder={append.placeholder}
            color={append.color}
            last={trailing}
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
  walk(childrenOf.get(null) ?? [], 0, null, null, true);

  const capabilityRow =
    detail?.role === "capability" ? byId.get(detail.goal.id!) : undefined;
  const capabilityTopic =
    capabilityRow?.parent != null ? byId.get(capabilityRow.parent) : undefined;

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
        <WordingSwitch full={fullWording} onChange={setFullWording} />
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
        {layout === "table" && !filtering && (
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

      <div className="flex flex-col gap-3 lg:flex-row lg:items-start">
      <div className="min-w-0 flex-1 overflow-hidden rounded-xl border border-hestia-border bg-hestia-surface shadow-sm">
        {/* pb-4 keeps the trailing append knob inside the scroll area instead of under its edge. */}
        <div ref={scrollerRef} className="relative max-h-[72vh] overflow-auto pb-4">
          <div
            role="table"
            aria-label="Competency tree"
            className="min-w-[880px]"
          >
            <div
              ref={headerRef}
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
      {/* The modals always get the freshest goal for their id, so in-modal edits survive refetches.
          A capability is built around the skills it contains, so it has a detail view of its own. */}
      <CapabilityModal
        goal={capabilityRow?.goal ?? null}
        number={capabilityRow?.number ?? ""}
        topicNumber={capabilityTopic?.number}
        topicLabel={capabilityTopic ? displayedGoalLabel(capabilityTopic.goal) : undefined}
        skills={(capabilityRow ? (childrenOf.get(capabilityRow.id) ?? []) : []).map((skill) => ({
          goal: skill.goal,
          number: skill.number,
          knowledge: (childrenOf.get(skill.id) ?? []).map((item) => item.goal),
        }))}
        onClose={closeDetail}
        onUpdate={onUpdate}
        onDelete={onDelete}
        onOpenGoal={openFromCapability}
      />
      <CompetencyGoalModal
        goal={
          detail && detail.role !== "capability"
            ? (byId.get(detail.goal.id!)?.goal ?? detail.goal)
            : null
        }
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
    for (let index = 0; index < node.children.length; index++) {
      walk(node.children[index], node.goal.id, `${number}.${index + 1}`);
    }
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
      className="relative flex items-center gap-0.5 px-2.5 py-2"
    >
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
      {popover}
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
  mapOpen,
  stickyTop,
  onToggle,
  onToggleMap,
  onOpen,
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
  stickyTop: number;
  onToggle: (id: number) => void;
  onToggleMap: (id: number) => void;
  onOpen: (row: Row) => void;
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
  /** Names the "+" action; absent when this row takes no children here. */
  addChildLabel?: string;
  onAddChild?: () => void;
}) {
  const meta = COMPETENCY_ROLE_META[row.role];
  const interactive = !context;
  const canToggle = layout === "table" && childCount > 0 && !filtering;
  // While browsing the map layout, a topic row opens its map; every other row opens its detail.
  const opensMap = layout === "map" && row.role === "topic" && !filtering;
  // Goal details are switched off in the table for now: a click there does nothing, and the row's
  // own controls (toggle, level, source, rename, delete, +) keep working.
  const activate = opensMap
    ? () => onToggleMap(row.id)
    : layout === "table"
      ? null
      : () => onOpen(row);
  // Role-tinted rail beside the name, so the tier reads at a glance; knowledge is faded so the
  // branch tiers (topic / capability / skill) and gaps stand out.
  const railColor =
    row.role === "knowledge"
      ? `color-mix(in srgb, ${meta.color} 55%, transparent)`
      : meta.color;
  const source = row.goal.sources?.[0];
  // The document names the source; the session is where in the course it sits. A session named
  // exactly like its file (one lecture per PDF) would only repeat the label, so it is left out.
  const documentLabel = source?.displayName || source?.filename || "Source document";
  const sessionLabel = row.session && row.session !== documentLabel ? row.session : null;
  // A figure source rests on the AI's description of a slide image rather than on quoted text.
  const figure = source?.evidenceKind === "FIGURE";
  return (
    <div
      role="row"
      data-row-id={row.id}
      {...(interactive && activate
        ? {
            tabIndex: 0,
            onClick: activate,
            onKeyDown: (e: ReactKeyboardEvent) => {
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
          ? "opacity-45"
          : mapOpen
            ? "cursor-pointer"
            : `${activate ? "cursor-pointer " : ""}hover:bg-[color-mix(in_srgb,var(--hestia-primary)_7%,transparent)]`
      }`}
      style={{
        gridTemplateColumns: GRID_COLS,
        // The sticky row paints over the map beneath it, so its background above is opaque.
        ...(mapOpen ? { position: "sticky", top: stickyTop, zIndex: 5 } : {}),
      }}
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
            className={`pt-px text-sm leading-relaxed text-hestia-text ${
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
            {row.role === "skill" && (
              <span className="ml-1.5">
                <CoverageBadge goal={row.goal} flag={levelFlag} />
              </span>
            )}
            {opensMap && (
              // The map starts below the topic, so the topic's own detail (edit, delete) opens here.
              <button
                type="button"
                aria-label="Open topic details"
                title="Open topic details"
                onClick={(e) => {
                  e.stopPropagation();
                  onOpen(row);
                }}
                onKeyDown={(e) => e.stopPropagation()}
                className={`ml-1.5 inline-flex h-6 w-6 items-center justify-center rounded-md align-middle text-hestia-text-muted transition hover:bg-hestia-primary-muted hover:text-hestia-text focus-visible:opacity-100 ${
                  mapOpen ? "" : "opacity-0 group-hover:opacity-100"
                }`}
              >
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
                  <circle cx="10" cy="10" r="7" />
                  <path d="M10 9v4.5M10 6.5v.01" />
                </svg>
              </button>
            )}
            {coverage && coverage.total > coverage.unknown && (
              <span className="block text-xs font-normal text-hestia-text-muted">
                {coverage.practised} of {coverage.total} sub-skills practised
              </span>
            )}
          </span>
          )}
          {interactive && !editing && (
            // Revealed on hover (or keyboard focus), so resting rows read as plain text.
            <span className="ml-auto flex shrink-0 items-center gap-0.5 opacity-0 transition focus-within:opacity-100 group-hover:opacity-100">
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
      <div role="gridcell" className="px-2.5 py-1.5">
        <Pill label={meta.label} color={meta.color} />
      </div>
      <div role="gridcell" className="px-2.5 py-1.5">
        {row.goal.creationProvenance === "WIZARD_AI_SUBTREE" ? (
          <Pill label="AI-inferred" color="var(--hestia-danger)" />
        ) : row.goal.creationProvenance === "USER_CREATED" ? (
          <Pill label="Manual" color="var(--hestia-warning)" />
        ) : row.goal.kind && !isGrouping(row.role) ? (
          <Pill
            label={titleCase(row.goal.kind)}
            color="var(--hestia-text-muted)"
          />
        ) : (
          <span className="text-xs text-hestia-text-muted">—</span>
        )}
      </div>
      {(["bloom", "solo"] as const).map((scale) => (
        <div key={scale} role="gridcell" className="min-w-0 px-2.5 py-1.5">
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
        </div>
      ))}
      <div
        role="gridcell"
        className="min-w-0 px-2.5 py-1.5 text-xs text-hestia-text-muted"
      >
        {!source ? (
          "—"
        ) : (
          <button
            type="button"
            disabled={context}
            aria-pressed={sourceOpen}
            title={`${documentLabel}${source.page ? ` · page ${source.page}` : ""}${
              sessionLabel ? `\nSession: ${sessionLabel}` : ""
            }${
              figure && source.figureDescription
                ? `\nFigure (AI description): ${source.figureDescription}`
                : ""
            }`}
            onClick={(e) => {
              e.stopPropagation();
              onOpenSource(row, e.currentTarget);
            }}
            // The row itself opens the goal on click and on Enter/Space, so this control has to
            // keep both to itself.
            onKeyDown={(e) => e.stopPropagation()}
            className={`flex max-w-full min-w-0 flex-col items-start rounded-md border px-1.5 py-0.5 text-left transition ${
              sourceOpen
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
        )}
      </div>
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

/** Icon button in a row's hover actions; keeps its click and keys away from the row itself. */
function RowAction({
  label,
  onClick,
  className,
  children,
}: {
  label: string;
  onClick: () => void;
  className: string;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      onKeyDown={(e) => e.stopPropagation()}
      className={`flex h-6 w-6 items-center justify-center rounded-md text-hestia-text-muted transition ${className}`}
    >
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
        {children}
      </svg>
    </button>
  );
}

/**
 * In-place rename of a goal's full wording. Enter or leaving the field saves, Escape cancels;
 * `onDone` gets the new text, or `null` when nothing changed or the edit was dropped.
 */
function RenameField({
  text,
  onDone,
}: {
  text: string;
  onDone: (text: string | null) => void;
}) {
  const [draft, setDraft] = useState(text);
  const ref = useRef<HTMLTextAreaElement>(null);
  // Enter and Escape unmount the field, which can blur it once more on the way out.
  const finished = useRef(false);
  const finish = (save: boolean) => {
    if (finished.current) return;
    finished.current = true;
    const trimmed = draft.trim();
    onDone(save && trimmed !== "" && trimmed !== text ? trimmed : null);
  };
  useLayoutEffect(() => {
    const field = ref.current;
    if (!field) return;
    field.focus();
    field.setSelectionRange(field.value.length, field.value.length);
  }, []);
  return (
    <textarea
      ref={ref}
      value={draft}
      rows={1}
      aria-label="Goal wording"
      onChange={(e) => setDraft(e.target.value)}
      onClick={(e) => e.stopPropagation()}
      onBlur={() => finish(true)}
      onKeyDown={(e) => {
        // The row opens the goal on Enter/Space, and the grid closes things on Escape.
        e.stopPropagation();
        if (e.key === "Enter" && !e.shiftKey) {
          e.preventDefault();
          finish(true);
        } else if (e.key === "Escape") {
          e.preventDefault();
          finish(false);
        }
      }}
      className="min-w-0 flex-1 resize-none rounded-sm border-[1.5px] border-hestia-primary bg-hestia-bg px-1.5 py-0.5 text-sm leading-relaxed text-hestia-text shadow-[0_0_0_3px_var(--hestia-primary-muted)] outline-none [field-sizing:content]"
    />
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
  if (!interactive) {
    return <span className="text-xs text-hestia-text-muted">{term ?? "—"}</span>;
  }
  const ladder = Object.keys(meta.desc);
  const index = term == null ? -1 : ladder.indexOf(term);
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
        className={`max-w-full truncate rounded-md px-1.5 py-0.5 text-xs transition ${
          term
            ? `text-hestia-text hover:bg-hestia-text/5 ${open ? "bg-hestia-text/5" : ""}`
            : "border border-dashed border-hestia-border text-hestia-text-muted hover:border-hestia-primary hover:text-hestia-primary"
        }`}
      >
        {term ?? "Set"}
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
                <span className="mt-2 flex shrink-0 gap-0.5" aria-hidden="true">
                  {ladder.map((dot, j) => (
                    <span
                      key={dot}
                      className={`h-1.5 w-1.5 rounded-full ${j <= i ? meta.dotClass : "bg-hestia-text/15"}`}
                    />
                  ))}
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

/** Small tinted attribute pill, coloured via a HESTIA CSS variable so it tracks the theme. */
function Pill({ label, color }: { label: string; color: string }) {
  return (
    <span
      className="inline-flex items-center whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-semibold"
      style={{
        color,
        backgroundColor: `color-mix(in srgb, ${color} 15%, transparent)`,
      }}
    >
      {label}
    </span>
  );
}

/**
 * Segmented control switching the grid between its two layouts. Follows the styleguide's toggle:
 * one surface pill, the selected segment filled with primary. The end segments carry the rounding
 * themselves rather than the track clipping them, so the focus ring stays visible.
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
    { key: "map", label: "Map", icon: <MapIcon /> },
  ];
  return (
    <div
      role="tablist"
      aria-label="Tree layout"
      className="inline-flex rounded-full border border-hestia-border bg-hestia-surface"
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
            className={`inline-flex items-center gap-1.5 px-4 py-2 text-sm transition first:rounded-l-full last:rounded-r-full ${
              active
                ? "bg-hestia-primary font-semibold text-hestia-on-primary"
                : "font-medium text-hestia-text-muted hover:text-hestia-text"
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

/** Toolbar switch between short labels and every goal's full wording in the goal column. */
function WordingSwitch({
  full,
  onChange,
}: {
  full: boolean;
  onChange: (full: boolean) => void;
}) {
  const options = [
    { full: false, label: "Short names" },
    { full: true, label: "Full names" },
  ];
  return (
    <div
      role="radiogroup"
      aria-label="Goal wording"
      title="Show short labels or each goal's full wording"
      className="inline-flex rounded-full border border-hestia-border bg-hestia-surface"
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
            className={`px-3 py-1.5 text-xs transition first:rounded-l-full last:rounded-r-full ${
              active
                ? "bg-hestia-primary font-semibold text-hestia-on-primary"
                : "font-medium text-hestia-text-muted hover:text-hestia-text"
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
function MapIcon() {
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
