import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
} from "react";
import type { LearningGoal } from "../api/client.ts";
import CompetencyCreationField from "./CompetencyCreationField.tsx";
import { RenameField, RowAction } from "./GoalInlineEditing.tsx";
import {
  RoleBadge,
  AiInferredBadge,
  ManualBadge,
} from "./GoalBadges.tsx";
import {
  COMPETENCY_ROLE_META,
  displayedGoalLabel,
  tierNoun,
  type CompetencyNode,
  type CompetencyRole,
} from "../lib/goals.ts";

// Box geometry, kept in sync with the Tailwind classes below so the SVG connectors can be drawn
// from the layout alone (no DOM measuring): w-40 = 10rem, w-56 = 14rem, w-60 = 15rem and
// gap-3 = 0.75rem at a 16px root.
const BOX_W = 224;
const COMPACT_W = 160; // dimmed sibling boxes in a row with a focused box
const LEAF_W = 240; // boxes in the second and third rows
const GHOST_W = 128; // the quiet "+ New …" pill beside a row
const HINT_W = 168; // the note standing in for the pill beside the "Additional sub-skills" row
const GAP = 12;
const CONNECTOR_H = 40;
const SCROLL_STEP = 332; // one scroll-arrow press
const EDGE_W = 56; // the scroll fade at either edge (w-14)

function rowWidth(widths: number[]) {
  return widths.reduce((total, width) => total + width, 0) +
    Math.max(0, widths.length - 1) * GAP;
}

/** Centre positions measured from the left edge of a flex row with the given child widths. */
function childCentres(widths: number[]) {
  let left = 0;
  return widths.map((width) => {
    const centre = left + width / 2;
    left += width + GAP;
    return centre;
  });
}

/**
 * Space a row's creation control takes to the left of its boxes. An open creation field needs a
 * box's width.
 */
function ghostReserve(present: boolean, active: boolean) {
  if (!present) return 0;
  return (active ? BOX_W : GHOST_W) + GAP;
}

/**
 * The "add a goal here" state the table owns. The map only renders the creation controls, so a
 * creation started in the map and one started in the table can never both be open.
 */
export type MapCreation = {
  activeKey: string | null;
  value: string;
  pending: boolean;
  /** The failure of the creation currently open, if any. */
  error: string | undefined;
  begin: (tier: 2 | 3 | 4, parentGoalId: number) => void;
  change: (value: string) => void;
  submit: () => void;
  cancel: () => void;
};

/**
 * One topic as a focus-and-context tree, opened inline under its table row. The pinned row above
 * names the topic, so the diagram starts at its skills and direct sub-skills, with connectors
 * rising to that row. Clicking a box that holds goals drills in: it takes the focus with its
 * children in a row beneath it, while its siblings stay visible as subdued, directly selectable
 * context; clicking it again folds it. A focused skill's sub-skills drill in the same way, so
 * knowledge opens as a third row. A box that is not open names its first children as mini boxes
 * hanging beneath it; clicking one opens that box and briefly highlights the child in its row. Every box renames and deletes in
 * place, as the topic row does. The tree shares one horizontal scroll container so boxes and
 * connectors stay aligned; each focus change centres the deepest focused node.
 */
export default function TopicMap({
  topic,
  sequence,
  editingId,
  onStartEdit,
  onEndEdit,
  onDelete,
  onClose,
  creation,
  suspendEscape,
  fullWording,
  attributesOf,
}: {
  topic: CompetencyNode;
  /** The topic's lecture-order number in the table, e.g. "3". */
  sequence: string;
  /** The goal whose wording is being renamed in place, anywhere in the grid. */
  editingId: number | null;
  onStartEdit: (goal: LearningGoal) => void;
  /** Ends a rename; `text` is the new wording, or null when nothing is to be saved. */
  onEndEdit: (goal: LearningGoal, text: string | null) => void;
  onDelete: (goal: LearningGoal) => void;
  onClose: () => void;
  creation: MapCreation;
  /** Something above the map (the detail modal, a filter popover) owns Escape right now. */
  suspendEscape: boolean;
  /** Show every goal's full wording rather than its short label, as the table does. */
  fullWording: boolean;
  /** The goal's attributes in the table's visible columns, shown beneath a box's wording. */
  attributesOf: (node: CompetencyNode) => ReactNode;
}) {
  const actions = { editingId, onStartEdit, onEndEdit, onDelete };
  // The focus path: a box in the first row, then one of its sub-skills.
  const [focusedId, setFocusedId] = useState<number | null>(null);
  const [subFocusedId, setSubFocusedId] = useState<number | null>(null);
  // The child a mini box was clicked for: centred and outlined for a moment once its row is in.
  const [highlightId, setHighlightId] = useState<number | null>(null);
  useEffect(() => {
    if (highlightId == null) return;
    const timer = window.setTimeout(() => setHighlightId(null), 1600);
    return () => window.clearTimeout(timer);
  }, [highlightId]);
  // As in the table, the skills come first and the goals hanging directly off the topic follow in
  // one "Additional sub-skills" box. That box is no goal: it takes the negated topic id, which
  // no goal id collides with, and opens its goals as a skill opens its sub-skills.
  const skills = topic.children.filter((node) => node.role === "capability");
  const loose = topic.children.filter((node) => node.role !== "capability");
  const firstRow: CompetencyNode[] =
    loose.length > 0
      ? [
          ...skills,
          { goal: { id: -topic.goal.id!, text: LOOSE_GROUP_LABEL }, role: "capability", children: loose },
        ]
      : skills;
  const focused =
    firstRow.find((node) => node.goal.id === focusedId && canDrill(node)) ?? null;
  const focusedIsGroup = focused != null && isLooseGroup(focused);
  const subFocused =
    focused?.role === "capability"
      ? (focused.children.find((node) => node.goal.id === subFocusedId && canDrill(node)) ??
        null)
      : null;

  // A reload/re-extraction can drop a focused goal; fall back one level. Plain state change —
  // nothing was clicked, so nothing should animate.
  useEffect(() => {
    if (focusedId != null && !focused) setFocusedId(null);
    if (subFocusedId != null && !subFocused) setSubFocusedId(null);
  }, [focusedId, focused, subFocusedId, subFocused]);

  // FLIP: `navigate` snapshots every visible box (by goal id) before the focus changes; after the
  // new layout is in, each box that survived the transition — the clicked one gliding into its
  // focus slot — is animated from its old viewport rect to its new one. Boxes without a previous
  // rect simply appear (their `comp-pop` entrance), so a programmatic focus change animates nothing.
  const containerRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const flipRects = useRef<Map<string, DOMRect> | null>(null);
  const [scrollEdges, setScrollEdges] = useState({ left: false, right: false });

  const navigate = (
    next: number | null,
    nextSub: number | null = null,
    highlight: number | null = null,
  ) => {
    const map = new Map<string, DOMRect>();
    containerRef.current
      ?.querySelectorAll<HTMLElement>("[data-goal-id]")
      .forEach((el) => map.set(el.dataset.goalId!, el.getBoundingClientRect()));
    flipRects.current = map;
    setFocusedId(next);
    setSubFocusedId(nextSub);
    setHighlightId(highlight);
  };

  const deepest = subFocused ?? focused;
  useLayoutEffect(() => {
    // Centre the highlighted child, else the deepest focused node, rather than the entire canvas,
    // so the node being opened is immediately readable even when a row overflows the scroller.
    const scroller = scrollRef.current;
    const centreId = highlightId ?? deepest?.goal.id;
    const focusedBox =
      centreId != null
        ? containerRef.current?.querySelector<HTMLElement>(`[data-goal-id="${centreId}"]`)
        : null;
    if (scroller && focusedBox) {
      const scrollerRect = scroller.getBoundingClientRect();
      const focusedRect = focusedBox.getBoundingClientRect();
      scroller.scrollLeft +=
        focusedRect.left -
        scrollerRect.left -
        scroller.clientWidth / 2 +
        focusedRect.width / 2;
    } else if (scroller) {
      scroller.scrollLeft = (scroller.scrollWidth - scroller.clientWidth) / 2;
    }

    const prev = flipRects.current;
    flipRects.current = null;
    if (
      !prev ||
      window.matchMedia("(prefers-reduced-motion: reduce)").matches
    )
      return;
    containerRef.current
      ?.querySelectorAll<HTMLElement>("[data-goal-id]")
      .forEach((el) => {
        const from = prev.get(el.dataset.goalId!);
        if (!from) return;
        const to = el.getBoundingClientRect();
        const dx = from.left - to.left;
        const dy = from.top - to.top;
        const sx = from.width / to.width;
        const sy = from.height / to.height;
        if (Math.abs(dx) < 1 && Math.abs(dy) < 1 && Math.abs(sx - 1) < 0.01)
          return;
        // A surviving box slides — it must not ALSO play an entrance animation, neither its
        // own nor an enclosing wrapper's (the row cells carry `comp-pop`).
        for (
          let n: HTMLElement | null = el;
          n && n !== containerRef.current;
          n = n.parentElement
        ) {
          n.getAnimations().forEach((a) => {
            if (a instanceof CSSAnimation && a.animationName.startsWith("comp-"))
              a.cancel();
          });
        }
        el.style.transformOrigin = "top left";
        el.animate(
          [
            { transform: `translate(${dx}px, ${dy}px) scale(${sx}, ${sy})` },
            { transform: "none" },
          ],
          { duration: 300, easing: "cubic-bezier(0.2, 0, 0.2, 1)" },
        );
      });
    // The highlight only steers this first centring; its fade-out must not scroll again. Keyed on
    // ids, not nodes: the "Additional sub-skills" box is rebuilt on every render, and a re-render
    // (the edge fades toggling at the end of a scroll) must not snap the view back.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusedId, subFocusedId, deepest?.goal.id]);

  // The canvas is deterministic, but its rendered width depends on the current tree. Observe both
  // the scrollport and its canvas so the cosmetic affordances only appear when overflow exists;
  // the scroll listener keeps the visible edge fades in sync while the user or an arrow scrolls.
  useEffect(() => {
    const scroller = scrollRef.current;
    if (!scroller) return;

    const updateScrollEdges = () => {
      const maxScroll = Math.max(0, scroller.scrollWidth - scroller.clientWidth);
      const next = {
        left: maxScroll > 1 && scroller.scrollLeft > 1,
        right: maxScroll > 1 && scroller.scrollLeft < maxScroll - 1,
      };
      setScrollEdges((previous) =>
        previous.left === next.left && previous.right === next.right
          ? previous
          : next,
      );
    };
    const canvas = scroller.firstElementChild;
    const resizeObserver = new ResizeObserver(updateScrollEdges);
    resizeObserver.observe(scroller);
    if (canvas instanceof HTMLElement) resizeObserver.observe(canvas);
    scroller.addEventListener("scroll", updateScrollEdges, { passive: true });
    updateScrollEdges();

    return () => {
      resizeObserver.disconnect();
      scroller.removeEventListener("scroll", updateScrollEdges);
    };
  }, [focused?.goal.id, subFocused?.goal.id]);

  // Clicking a focused box again folds it back into its parent's overview.
  const pickFirst = (id: number) => navigate(focusedId === id ? null : id);
  const pickSecond = (id: number) => navigate(focusedId, subFocusedId === id ? null : id);

  // Escape retraces the path: out of a sub-skill, then out of a skill, then closes the map.
  // Whatever owns Escape above the map (the detail modal, a filter popover, an open creation field
  // that prevents the default) is left alone.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (
        e.key !== "Escape" ||
        e.defaultPrevented ||
        suspendEscape ||
        document.querySelector('[role="dialog"]')
      )
        return;
      if (subFocused) navigate(focusedId);
      else if (focused) navigate(null);
      else onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  // Row widths: in a row with a focused box, the focused one keeps its normal width while the
  // dimmed context boxes compact to w-40. Positions are measured from the centre of the first row
  // (every row centres itself between symmetric creation reserves), so the rows, the connectors
  // and the branches stay aligned without DOM measuring.
  const firstKey = `2:${topic.goal.id}`;
  // A topic takes any number of skills, so its "New skill" ghost is always there.
  const firstReserve = ghostReserve(true, creation.activeKey === firstKey);
  const firstWidths = firstRow.map((child) =>
    focused && child.goal.id !== focused.goal.id ? COMPACT_W : BOX_W,
  );
  const focusedIndex = focused
    ? firstRow.findIndex((node) => node.goal.id === focused.goal.id)
    : -1;
  const secondOffset =
    focusedIndex >= 0
      ? childCentres(firstWidths)[focusedIndex] - rowWidth(firstWidths) / 2
      : 0;

  // A skill's children are sub-skills; a sub-skill hanging directly off the topic holds knowledge.
  const secondTier = focused?.role === "capability" ? 3 : 4;
  const secondKey = focused ? `${secondTier}:${focused.goal.id}` : "";
  // A sub-skill added straight under the topic would be read back as a skill, so the group offers
  // no "New sub-skill"; a note in its place points to where sub-skills are added instead.
  const secondReserve = focusedIsGroup
    ? HINT_W + GAP
    : ghostReserve(focused != null, creation.activeKey === secondKey);
  // The table's numbers: a goal in the group continues the topic's count after its skills.
  const secondSequence = (index: number) =>
    focusedIsGroup
      ? `${sequence}.${skills.length + index + 1}`
      : `${sequence}.${focusedIndex + 1}.${index + 1}`;
  const secondWidths = focused
    ? focused.children.map((child) =>
        subFocused && child.goal.id !== subFocused.goal.id ? COMPACT_W : LEAF_W,
      )
    : [];
  const subFocusedIndex =
    focused && subFocused
      ? focused.children.findIndex((node) => node.goal.id === subFocused.goal.id)
      : -1;
  const thirdOffset =
    subFocusedIndex >= 0
      ? childCentres(secondWidths)[subFocusedIndex] - rowWidth(secondWidths) / 2
      : 0;

  const thirdKey = subFocused ? `4:${subFocused.goal.id}` : "";
  const thirdReserve = ghostReserve(subFocused != null, creation.activeKey === thirdKey);
  const thirdWidths = subFocused ? subFocused.children.map(() => LEAF_W) : [];

  // Relative positioning paints a branch at its focused parent, but does not enlarge the canvas.
  // The canvas is as wide as its widest row of boxes with every row centred in it; these paddings
  // cover how far a shifted branch or a ghost reaches past that width at either edge, making it
  // scrollable.
  // Each row's creation ghost sits to the left of its boxes, outside the row's own width.
  const rows = [
    { centre: 0, half: rowWidth(firstWidths) / 2, reserve: firstReserve },
    ...(focused
      ? [{ centre: secondOffset, half: rowWidth(secondWidths) / 2, reserve: secondReserve }]
      : []),
    ...(subFocused
      ? [
          {
            centre: secondOffset + thirdOffset,
            half: rowWidth(thirdWidths) / 2,
            reserve: thirdReserve,
          },
        ]
      : []),
  ];
  // Each side also keeps the scroll fade's width, so whatever sits at an edge (a creation ghost,
  // an outer box) can be scrolled clear of the fade and its arrow.
  const canvasHalf = Math.max(...rows.map((row) => row.half));
  const padLeft =
    EDGE_W +
    Math.max(0, ...rows.map((row) => row.half + row.reserve - row.centre - canvasHalf));
  const padRight =
    EDGE_W + Math.max(0, ...rows.map((row) => row.half + row.centre - canvasHalf));

  const ghost = (label: string, color: string, tier: 2 | 3 | 4, key: string, parentId: number) => (
    <CreationGhost
      label={label}
      color={color}
      active={creation.activeKey === key}
      value={creation.value}
      pending={creation.pending}
      error={creation.activeKey === key ? creation.error : undefined}
      onStart={() => creation.begin(tier, parentId)}
      onChange={creation.change}
      onSubmit={creation.submit}
      onCancel={creation.cancel}
    />
  );

  return (
    <div ref={containerRef} className="flex flex-col px-3 pb-4">
      <div className="relative">
        <div ref={scrollRef} className="scrollbar-none overflow-x-auto pb-2">
          {/* Keyed by the focus path so every navigation remounts the tiers — the connectors
              redraw and the children replay their entrance, while surviving boxes FLIP. */}
          <div
            key={`tree-${topic.goal.id}-${focused?.goal.id ?? "none"}-${subFocused?.goal.id ?? "none"}`}
            className="mx-auto flex w-max min-w-full flex-col items-center"
            style={{ paddingLeft: padLeft, paddingRight: padRight }}
          >
            {/* Rises to the topic row above; in a drilled-in state it leads to the focused box. */}
            <Connector
              childWidths={firstWidths}
              color={COMPETENCY_ROLE_META.topic.color}
              focusedIndex={focused ? focusedIndex : undefined}
            />
            <CreationRow
              reserve={firstReserve}
              ghost={ghost("New skill", COMPETENCY_ROLE_META.capability.color, 2, firstKey, topic.goal.id!)}
            >
              {firstRow.map((child, i) => {
                const isFocused = focused != null && child.goal.id === focused.goal.id;
                const dimmed = focused != null && !isFocused;
                const group = isLooseGroup(child);
                return (
                  // Column cell: the box plus, while it is not open, what it holds beneath it.
                  <div
                    key={child.goal.id}
                    className="comp-pop flex flex-col items-center"
                    style={{ animationDelay: `${180 + i * 30}ms` }}
                  >
                    <Box
                      node={child}
                      active={isFocused}
                      expandable={canDrill(child)}
                      onClick={canDrill(child) ? () => pickFirst(child.goal.id!) : undefined}
                      actions={actions}
                      dimmed={dimmed}
                      compact={dimmed}
                      clampText={dimmed}
                      group={group}
                      sequenceLabel={group ? undefined : `${sequence}.${i + 1}`}
                      fullWording={fullWording}
                      attributes={group ? null : attributesOf(child)}
                    />
                    {!isFocused && (
                      <ChildHint
                        node={child}
                        dimmed={dimmed}
                        onPick={(grandchild) =>
                          navigate(child.goal.id!, null, grandchild.goal.id!)
                        }
                        onMore={() => navigate(child.goal.id!)}
                      />
                    )}
                  </div>
                );
              })}
            </CreationRow>
            {focused && (
              // The focused box sits at a known fixed position in its row. Moving this whole
              // branch by that same offset makes its connector originate at the focused box
              // instead of the row centre.
              <div className="relative flex flex-col items-center" style={{ left: secondOffset }}>
                <Connector
                  childWidths={secondWidths}
                  color={COMPETENCY_ROLE_META[focused.role].color}
                  focusedIndex={subFocused ? subFocusedIndex : undefined}
                />
                <CreationRow
                  reserve={secondReserve}
                  ghost={
                    focusedIsGroup
                      ? (
                          // Drawn like the "+ New …" pill it stands in for, but as a note: an
                          // info mark instead of the plus, and nothing to click.
                          <p className="flex items-start gap-1.5 rounded-xl border border-dashed border-hestia-border px-3 py-1.5 text-xs leading-snug text-hestia-text-muted">
                            <svg
                              viewBox="0 0 20 20"
                              fill="none"
                              stroke="currentColor"
                              strokeWidth="1.8"
                              strokeLinecap="round"
                              aria-hidden="true"
                              className="mt-px h-3.5 w-3.5 shrink-0"
                            >
                              <circle cx="10" cy="10" r="7.25" />
                              <path d="M10 9v4.5M10 6.5v.01" />
                            </svg>
                            Add sub-skills under a skill, or add a new skill first.
                          </p>
                        )
                      : secondTier === 3
                      ? ghost("New sub-skill", COMPETENCY_ROLE_META.skill.color, 3, secondKey, focused.goal.id!)
                      : ghost("New knowledge", COMPETENCY_ROLE_META.knowledge.color, 4, secondKey, focused.goal.id!)
                  }
                >
                  {focused.children.map((child, i) => {
                    const isFocused = subFocused != null && child.goal.id === subFocused.goal.id;
                    const dimmed = subFocused != null && !isFocused;
                    const drillable = secondTier === 3 && canDrill(child);
                    return (
                      // The children pop in after the focused box has slid into place.
                      <div
                        key={child.goal.id}
                        className="comp-pop flex flex-col items-center"
                        style={{ animationDelay: `${240 + i * 30}ms` }}
                      >
                        <Box
                          node={child}
                          active={isFocused}
                          expandable={drillable}
                          onClick={drillable ? () => pickSecond(child.goal.id!) : undefined}
                          highlighted={highlightId === child.goal.id}
                          actions={actions}
                          leaf={!dimmed}
                          dimmed={dimmed}
                          compact={dimmed}
                          clampText={dimmed}
                          sequenceLabel={secondSequence(i)}
                          fullWording={fullWording}
                          attributes={attributesOf(child)}
                        />
                        {drillable && !isFocused && (
                          <ChildHint
                            node={child}
                            dimmed={dimmed}
                            onPick={(leaf) =>
                              navigate(focused.goal.id!, child.goal.id!, leaf.goal.id!)
                            }
                            onMore={() => navigate(focused.goal.id!, child.goal.id!)}
                          />
                        )}
                      </div>
                    );
                  })}
                </CreationRow>
                {subFocused && (
                  <div className="relative flex flex-col items-center" style={{ left: thirdOffset }}>
                    <Connector
                      childWidths={thirdWidths}
                      color={COMPETENCY_ROLE_META.skill.color}
                    />
                    <CreationRow
                      reserve={thirdReserve}
                      ghost={ghost("New knowledge", COMPETENCY_ROLE_META.knowledge.color, 4, thirdKey, subFocused.goal.id!)}
                    >
                      {subFocused.children.map((leaf, i) => (
                        <div
                          key={leaf.goal.id}
                          className="comp-pop"
                          style={{ animationDelay: `${240 + i * 30}ms` }}
                        >
                          <Box
                            node={leaf}
                            active={false}
                            expandable={false}
                            highlighted={highlightId === leaf.goal.id}
                            actions={actions}
                            leaf
                            sequenceLabel={`${secondSequence(subFocusedIndex)}.${i + 1}`}
                            fullWording={fullWording}
                            attributes={attributesOf(leaf)}
                          />
                        </div>
                      ))}
                    </CreationRow>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
        {scrollEdges.left && (
          <ScrollEdge side="left" onClick={() =>
            scrollRef.current?.scrollBy({ left: -SCROLL_STEP, behavior: "smooth" })
          } />
        )}
        {scrollEdges.right && (
          <ScrollEdge side="right" onClick={() =>
            scrollRef.current?.scrollBy({ left: SCROLL_STEP, behavior: "smooth" })
          } />
        )}
      </div>
    </div>
  );
}

const LOOSE_GROUP_LABEL = "Additional sub-skills";

/** The first row's box gathering the topic's goals that sit in no skill. */
function isLooseGroup(node: CompetencyNode) {
  return node.goal.id! < 0;
}

/** Skills and sub-skills that hold goals unfold into a row of their own; knowledge and gaps don't. */
function canDrill(node: CompetencyNode) {
  return (node.role === "capability" || node.role === "skill") && node.children.length > 0;
}

/** How many children a closed box names as mini boxes before summing up the rest. */
const MINI_LIMIT = 3;

/**
 * What a closed box holds, hanging beneath it: its first children as mini boxes on a thin line,
 * each opening the box with that child highlighted, then a "+N more" pill that opens the box. A dimmed
 * context box only hints with stub dots, so the focused branch stays the thing to read.
 */
function ChildHint({
  node,
  dimmed,
  onPick,
  onMore,
}: {
  node: CompetencyNode;
  dimmed: boolean;
  onPick: (child: CompetencyNode) => void;
  onMore: () => void;
}) {
  if (node.children.length === 0 || node.role === "knowledge" || node.role === "gap") return null;
  if (dimmed) return <LeafStub count={node.children.length} />;
  const shown = node.children.slice(0, MINI_LIMIT);
  const rest = node.children.length - shown.length;
  const lineColor = COMPETENCY_ROLE_META[shown[0].role].color;
  return (
    <div className="relative flex flex-col items-center gap-1 pt-3.5">
      <span
        aria-hidden="true"
        className="absolute top-0 bottom-3 left-1/2 w-[1.5px] -translate-x-1/2 opacity-55"
        style={{ backgroundColor: lineColor }}
      />
      {shown.map((child) => {
        const color = COMPETENCY_ROLE_META[child.role].color;
        const label = displayedGoalLabel(child.goal);
        return (
          <button
            key={child.goal.id}
            type="button"
            title={child.goal.text ?? label}
            onClick={(e) => {
              e.stopPropagation();
              onPick(child);
            }}
            className="relative flex h-6 w-44 items-center gap-1.5 rounded-md border-[1.5px] bg-hestia-surface px-2 text-left text-[11px] text-hestia-text transition hover:bg-hestia-bg"
            style={{ borderColor: `color-mix(in srgb, ${color} 55%, transparent)` }}
          >
            <span
              aria-hidden="true"
              className="h-1.5 w-1.5 shrink-0 rounded-full"
              style={{ backgroundColor: color }}
            />
            <span className="min-w-0 truncate">{label}</span>
          </button>
        );
      })}
      {rest > 0 && (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onMore();
          }}
          className="relative h-[22px] rounded-full border-[1.5px] border-dashed border-hestia-border bg-hestia-surface px-2.5 text-[11px] text-hestia-text-muted transition hover:border-hestia-primary hover:text-hestia-text"
        >
          +{rest} more
        </button>
      )}
    </div>
  );
}

/**
 * A row of boxes with its creation control to their left. The control hangs outside the row's
 * width, so the boxes stay centred exactly under the connector drawn for them alone; the canvas
 * padding makes room for it. It is centred on the row's first box, the one beside it, rather than
 * on the whole row, whose height also counts the child hints beneath the boxes.
 */
function CreationRow({
  reserve,
  ghost,
  children,
}: {
  reserve: number;
  ghost: ReactNode;
  children: ReactNode;
}) {
  const rowRef = useRef<HTMLDivElement>(null);
  // The first box's vertical centre within the row; null while the row has no box.
  const [anchorTop, setAnchorTop] = useState<number | null>(null);
  useLayoutEffect(() => {
    const box = rowRef.current?.querySelector<HTMLElement>("[data-goal-id]");
    if (!box) {
      setAnchorTop(null);
      return;
    }
    // Offsets, not client rects: they ignore the transforms of the entrance and FLIP animations.
    const update = () => setAnchorTop(box.offsetTop + box.offsetHeight / 2);
    update();
    const observer = new ResizeObserver(update);
    observer.observe(box);
    return () => observer.disconnect();
    // The boxes are the children; a new set can put a different box first.
  }, [children]);
  return (
    <div ref={rowRef} className="relative flex justify-center gap-3">
      {children}
      {ghost && reserve > 0 && (
        <div
          className="absolute flex -translate-y-1/2 justify-end"
          style={{
            top: anchorTop ?? "50%",
            right: `calc(100% + ${GAP}px)`,
            width: reserve - GAP,
          }}
        >
          {ghost}
        </div>
      )}
    </div>
  );
}

/**
 * Edge fade with a scroll arrow, shown only on a side the map overflows. It stays beneath the
 * pinned topic row and the grid header, and its arrow sticks to the middle of the visible part of
 * a diagram taller than the view.
 */
function ScrollEdge({
  side,
  onClick,
}: {
  side: "left" | "right";
  onClick: () => void;
}) {
  const left = side === "left";
  return (
    <div
      className={`pointer-events-none absolute inset-y-0 z-[4] flex w-14 items-start py-4 ${
        left ? "left-0 justify-start pl-1" : "right-0 justify-end pr-1"
      }`}
      style={{
        background: `linear-gradient(to ${left ? "right" : "left"}, var(--hestia-bg), color-mix(in srgb, var(--hestia-bg) 80%, transparent), transparent)`,
      }}
    >
      <button
        type="button"
        aria-label={`Scroll topic diagram ${side}`}
        className="pointer-events-auto sticky top-1/2 rounded-full border border-hestia-border bg-hestia-bg/90 p-1 text-hestia-text-muted shadow-sm transition hover:bg-hestia-surface hover:text-hestia-text"
        onClick={onClick}
      >
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
          <path d={left ? "M12.5 4.5 7 10l5.5 5.5" : "m7.5 4.5 5.5 5.5-5.5 5.5"} />
        </svg>
      </button>
    </div>
  );
}

/**
 * Creation control beside a row: a quiet dashed pill that only takes the role colour on hover, and
 * the creation field once started.
 */
function CreationGhost({
  label,
  color,
  active,
  value,
  pending,
  error,
  onStart,
  onChange,
  onSubmit,
  onCancel,
}: {
  label: string;
  color: string;
  active: boolean;
  value: string;
  pending: boolean;
  error?: string;
  onStart: () => void;
  onChange: (value: string) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  const style = { "--competency-ghost-color": color } as CSSProperties;
  if (active) {
    return (
      <div
        className="competency-ghost competency-ghost-active w-56 shrink-0"
        style={style}
      >
        <CompetencyCreationField
          value={value}
          placeholder={`Describe a ${label.replace("New ", "").toLowerCase()}…`}
          error={error}
          pending={pending}
          onChange={onChange}
          onSubmit={onSubmit}
          onCancel={onCancel}
          // Box widths are fixed so the SVG connectors can be derived from the layout; a single-row
          // field would overflow them, so the buttons move below the input instead.
          stacked
        />
      </div>
    );
  }
  return (
    <button
      type="button"
      className="inline-flex items-center gap-1 whitespace-nowrap rounded-full border border-dashed border-hestia-border px-2.5 py-1 text-xs font-medium text-hestia-text-muted transition hover:border-[var(--competency-ghost-color)] hover:text-[var(--competency-ghost-color)] focus-visible:border-[var(--competency-ghost-color)] focus-visible:text-[var(--competency-ghost-color)]"
      style={style}
      disabled={pending}
      onClick={onStart}
    >
      <span aria-hidden="true" className="text-sm leading-none">
        +
      </span>
      {label}
    </button>
  );
}

/**
 * Tree connector between a centred parent and its children's row. The trunk drops from the row's
 * midpoint, turns onto a horizontal rail and drops a stub to each child — the standard tree
 * routing, mirrored for children left of the trunk; joins are rounded so there are no sharp
 * corners. Child centres are derived from their explicit widths, so compact siblings and leaf
 * boxes remain aligned without DOM measuring.
 */
function Connector({
  childWidths,
  color,
  focusedIndex,
}: {
  childWidths: number[];
  color: string;
  /** Dims every link but the one to the focused child. */
  focusedIndex?: number;
}) {
  const count = childWidths.length;
  if (count === 0) return null;
  const width = rowWidth(childWidths);
  const centres = childCentres(childWidths);
  const trunk = width / 2;
  const mid = CONNECTOR_H / 2;
  const r = 8; // corner radius for the rounded joins

  // `pathLength={1}` normalises every path so the comp-draw dash animation can draw each link
  // from the trunk toward its child, whatever the actual path length.
  return (
    <svg
      width={width}
      height={CONNECTOR_H}
      className="comp-draw block shrink-0"
      aria-hidden="true"
    >
      <g stroke={color} strokeWidth={1.5} fill="none" strokeLinecap="round">
        {Array.from({ length: count }, (_, i) => {
          const cx = centres[i];
          const dx = cx - trunk;
          const dimmed =
            focusedIndex != null && i !== focusedIndex ? 0.38 : undefined;
          // Too close to the trunk for the rail-and-corners route: a gentle S-curve instead
          // (straight drop when the child sits exactly under the trunk).
          if (Math.abs(dx) < 2 * r) {
            return (
              <path
                key={i}
                pathLength={1}
                opacity={dimmed}
                d={`M ${trunk} 0 C ${trunk} ${mid} ${cx} ${mid} ${cx} ${CONNECTOR_H}`}
              />
            );
          }
          const sg = dx > 0 ? 1 : -1;
          return (
            <path
              key={i}
              pathLength={1}
              opacity={dimmed}
              d={`M ${trunk} 0 V ${mid - r} Q ${trunk} ${mid} ${trunk + sg * r} ${mid} H ${cx - sg * r} Q ${cx} ${mid} ${cx} ${mid + r} V ${CONNECTOR_H}`}
            />
          );
        })}
      </g>
    </svg>
  );
}

/** The rename and delete actions shared by every box, the same as a table row's. */
type GoalActions = {
  editingId: number | null;
  onStartEdit: (goal: LearningGoal) => void;
  onEndEdit: (goal: LearningGoal, text: string | null) => void;
  onDelete: (goal: LearningGoal) => void;
};

/** A readable capability/skill/knowledge rectangle. Beneath its wording it shows the attributes the
 * table currently shows as columns, then what it holds by tier ("3 sub-skills"), with an unfold
 * chevron on branch boxes. Rename and delete float top-right on hover, as on a table row.
 * Clicking the body folds or unfolds a box that holds goals. A dimmed sibling keeps only its
 * wording and count, so the focused branch stays readable. It is a div (not a button) so
 * the action and attribute buttons can nest. */
function Box({
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
  node: CompetencyNode;
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
  const meta = COMPETENCY_ROLE_META[node.role];
  const outline = group ? "var(--hestia-text-muted)" : meta.color;
  const isGap = node.role === "gap";
  const editing = actions.editingId === node.goal.id;
  const childCounts = new Map<CompetencyRole, number>();
  for (const child of node.children)
    childCounts.set(child.role, (childCounts.get(child.role) ?? 0) + 1);
  return (
    <div
      {...(onClick
        ? {
            role: "button",
            tabIndex: 0,
            "aria-expanded": active,
            onClick,
            onKeyDown: (e: ReactKeyboardEvent) => {
              if (e.target !== e.currentTarget) return;
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                onClick();
              }
            },
          }
        : {})}
      data-goal-id={node.goal.id}
      className={`group relative flex ${onClick ? "cursor-pointer" : ""} flex-col gap-1.5 rounded-lg border-[1.5px] ${group ? "border-dashed" : ""} p-3 text-left transition ${
        leaf ? "w-60 shrink-0" : compact ? "w-40 shrink-0" : "w-56 shrink-0"
      } ${
        isGap
          ? "border-hestia-danger/40 bg-[color-mix(in_srgb,var(--hestia-danger)_8%,var(--hestia-surface))] hover:border-hestia-danger"
          : node.role === "knowledge"
            ? "border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_2.5%,var(--hestia-surface))] hover:border-hestia-primary hover:bg-hestia-bg"
          : "border-hestia-border bg-hestia-surface hover:border-hestia-primary hover:bg-hestia-bg"
      } ${dimmed ? "opacity-60 hover:opacity-100" : ""} ${
        highlighted ? "ring-2 ring-hestia-primary ring-offset-2 ring-offset-hestia-bg" : ""
      }`}
      style={{
        // The whole outline carries the role colour, so the tier reads without a shadow or rail.
        ...(isGap ? {} : { borderColor: outline }),
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
          className={`text-sm font-medium leading-snug ${
            isGap ? "text-hestia-danger" : "text-hestia-text"
          } ${clampText ? "line-clamp-3" : ""}`}
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
        <div className="mt-auto flex items-center gap-1 pt-1 text-xs text-hestia-text-muted">
          <span className="tabular-nums">
            {[...childCounts]
              .map(([role, count]) => `${count} ${tierNoun(role, count)}`)
              .join(" · ")}
          </span>
          {expandable && (
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
              className={`h-3.5 w-3.5 transition-transform ${active ? "rotate-90" : ""}`}
            >
              <path d="M7 5l6 5-6 5" />
            </svg>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Mini leaf indicator under a dimmed context box: stub lines branching into a few dots,
 * hinting that another tier unfolds beneath it. The count is suggestive (capped at three); the
 * exact numbers already sit in the box's count line.
 */
function LeafStub({ count }: { count: number }) {
  const xs = count >= 3 ? [12, 30, 48] : count === 2 ? [21, 39] : [30];
  const lineColor = `color-mix(in srgb, ${COMPETENCY_ROLE_META.knowledge.color} 55%, transparent)`;
  const dotColor = `color-mix(in srgb, ${COMPETENCY_ROLE_META.knowledge.color} 55%, var(--hestia-surface))`;
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
