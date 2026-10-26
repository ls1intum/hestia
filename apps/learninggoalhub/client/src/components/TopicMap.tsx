import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import type { LearningGoal } from "../api/client.ts";
import { COMPETENCY_ROLE_META, type CompetencyNode } from "../lib/goals.ts";
import {
  BOX_W,
  COMPACT_W,
  LEAF_W,
  HINT_W,
  GAP,
  SCROLL_STEP,
  EDGE_W,
  rowWidth,
  childCentres,
  ghostReserve,
  LOOSE_GROUP_LABEL,
  isLooseGroup,
  canDrill,
  type MapCreation,
} from "./topicMap/layout.ts";
import { ChildHint, CreationRow, CreationGhost } from "./topicMap/Creation.tsx";
import { ScrollEdge, Connector } from "./topicMap/Connector.tsx";
import { Box } from "./topicMap/Box.tsx";

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
  const skills = topic.children.filter((node) => node.role === "skill");
  const loose = topic.children.filter((node) => node.role !== "skill");
  const firstRow: CompetencyNode[] =
    loose.length > 0
      ? [
          ...skills,
          { goal: { id: -topic.goal.id!, text: LOOSE_GROUP_LABEL }, role: "skill", children: loose },
        ]
      : skills;
  const focused =
    firstRow.find((node) => node.goal.id === focusedId && canDrill(node)) ?? null;
  const focusedIsGroup = focused != null && isLooseGroup(focused);
  const subFocused =
    focused?.role === "skill"
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
  const secondTier = focused?.role === "skill" ? 3 : 4;
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
              ghost={ghost("New skill", COMPETENCY_ROLE_META.skill.color, 2, firstKey, topic.goal.id!)}
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
                      ? ghost("New sub-skill", COMPETENCY_ROLE_META.subSkill.color, 3, secondKey, focused.goal.id!)
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
                      color={COMPETENCY_ROLE_META.subSkill.color}
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
