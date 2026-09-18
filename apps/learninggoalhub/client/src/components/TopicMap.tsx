import {
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from "react";
import type { LearningGoal } from "../api/client.ts";
import CompetencyCreationField from "./CompetencyCreationField.tsx";
import CoverageBadge from "./CoverageBadge.tsx";
import {
  RoleBadge,
  AiInferredBadge,
  ManualBadge,
} from "./CompetencyGoalModal.tsx";
import { COMPETENCY_ROLE_META, levelFlags, type CompetencyNode } from "../lib/goals.ts";

// Box geometry, kept in sync with the Tailwind classes below so the SVG connectors can be drawn
// from the layout alone (no DOM measuring): w-40 = 10rem, w-56 = 14rem, w-60 = 15rem and
// gap-3 = 0.75rem at a 16px root.
const BOX_W = 224;
const COMPACT_W = 160; // dimmed sibling boxes in a focused capability row
const LEAF_W = 240; // leaf boxes under a focused capability
const GHOST_W = 128; // the quiet "+ New …" pill beside a row
const GAP = 12;
const CONNECTOR_H = 40;
const SCROLL_STEP = 332; // one scroll-arrow press

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
 * Space a row reserves on BOTH sides for its creation control, so the boxes stay centred under
 * their connector while the control sits beside them. An open creation field needs a box's width.
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
  begin: (tier: 2 | 3, parentGoalId: number) => void;
  change: (value: string) => void;
  submit: () => void;
  cancel: () => void;
};

/**
 * One topic as a focus-and-context tree, opened inline under its table row. The pinned row above
 * names the topic, so the map starts at its capabilities and direct skills, with connectors rising
 * to that row. Clicking a capability drills in: it takes the focus with its skills beneath it,
 * while its siblings stay visible as subdued, directly selectable context; clicking it again folds
 * it. Skills and knowledge open the goal detail modal instead of unfolding — knowledge is evidence
 * for its skill, and the modal is where it is listed and edited — and a capability's own detail is
 * one of its box actions. The tree shares one horizontal scroll container so boxes and connectors
 * stay aligned; each focus change centres the focused node.
 */
export default function TopicMap({
  topic,
  sequence,
  onOpenDetail,
  onEdit,
  onDelete,
  onClose,
  creation,
  suspendEscape,
}: {
  topic: CompetencyNode;
  /** The topic's lecture-order number in the table, e.g. "3". */
  sequence: string;
  onOpenDetail: (node: CompetencyNode) => void;
  onEdit: (goal: LearningGoal) => void;
  onDelete: (goal: LearningGoal) => void;
  onClose: () => void;
  creation: MapCreation;
  /** Something above the map (the detail modal, a filter popover) owns Escape right now. */
  suspendEscape: boolean;
}) {
  const actions = { onEdit, onDelete };
  const flags = useMemo(() => levelFlags([topic]), [topic]);
  const [focusedId, setFocusedId] = useState<number | null>(null);
  const focused =
    topic.children.find((node) => node.goal.id === focusedId) ?? null;

  // A reload/re-extraction can drop the focused capability; fall back to the topic. Plain state
  // change — nothing was clicked, so nothing should animate.
  useEffect(() => {
    if (focusedId != null && !focused) setFocusedId(null);
  }, [focusedId, focused]);

  // FLIP: `navigate` snapshots every visible box (by goal id) before the focus changes; after the
  // new layout is in, each box that survived the transition — the clicked one gliding into its
  // focus slot — is animated from its old viewport rect to its new one. Boxes without a previous
  // rect simply appear (their `comp-pop` entrance), so a programmatic focus change animates nothing.
  const containerRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const flipRects = useRef<Map<string, DOMRect> | null>(null);
  const [scrollEdges, setScrollEdges] = useState({ left: false, right: false });

  const navigate = (next: number | null) => {
    const map = new Map<string, DOMRect>();
    containerRef.current
      ?.querySelectorAll<HTMLElement>("[data-goal-id]")
      .forEach((el) => map.set(el.dataset.goalId!, el.getBoundingClientRect()));
    flipRects.current = map;
    setFocusedId(next);
  };

  useLayoutEffect(() => {
    // Centre the active node, rather than the entire canvas, so its children are immediately
    // readable even when the expanded sibling row overflows the shared scroller.
    const scroller = scrollRef.current;
    const focusedBox = focused
      ? containerRef.current?.querySelector<HTMLElement>(
          `[data-goal-id="${focused.goal.id}"]`,
        )
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
  }, [focusedId, focused]);

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
  }, [focused]);

  // Clicking the focused capability again folds it back into the topic's overview.
  const pickCapability = (id: number) => navigate(focusedId === id ? null : id);

  // Escape retraces the path: out of a capability first, then closes the map. Whatever owns
  // Escape above the map (the detail modal, a filter popover, an open creation field that
  // prevents the default) is left alone.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (
        e.key !== "Escape" ||
        e.defaultPrevented ||
        suspendEscape ||
        document.querySelector('[role="dialog"]')
      )
        return;
      if (focusedId != null) navigate(null);
      else onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const capabilityKey = `2:${topic.goal.id}`;
  // A topic takes any number of skills, so its "New skill" ghost is always there.
  const capabilityReserve = ghostReserve(true, creation.activeKey === capabilityKey);
  const focusedSubIndex = focused
    ? topic.children.findIndex((node) => node.goal.id === focused.goal.id)
    : -1;
  // In the focused row, the active box keeps its normal width while dimmed context boxes compact
  // to w-40. Prefix sums keep the row, the connectors and the leaf branch aligned.
  const siblingWidths = topic.children.map((child) =>
    focused && child.goal.id !== focused.goal.id ? COMPACT_W : BOX_W,
  );
  const siblingRowWidth = rowWidth(siblingWidths);
  const siblingCentres = childCentres(siblingWidths);
  const focusedSubCentre =
    focusedSubIndex >= 0 ? siblingCentres[focusedSubIndex] : siblingRowWidth / 2;
  const focusedSubOffset =
    focusedSubIndex >= 0 ? focusedSubCentre - siblingRowWidth / 2 : 0;

  const skillKey = focused ? `3:${focused.goal.id}` : "";
  const leafWidths = focused ? focused.children.map(() => LEAF_W) : [];
  const leafReserve = ghostReserve(focused != null, creation.activeKey === skillKey);
  const leafBranchWidth = focused ? rowWidth(leafWidths) + 2 * leafReserve : 0;
  // Relative positioning paints the leaf branch at the focused node, but does not
  // enlarge the canvas. These paddings cover its overhang past the sibling row (and that row's
  // creation reserve) at either edge, making the whole shifted branch scrollable.
  const leafLeftOverhang = Math.max(
    0,
    leafBranchWidth / 2 - focusedSubCentre - capabilityReserve,
  );
  const leafRightOverhang = Math.max(
    0,
    focusedSubCentre + leafBranchWidth / 2 - siblingRowWidth - capabilityReserve,
  );

  const capabilityGhost = (
    <CreationGhost
      label="New skill"
      color={COMPETENCY_ROLE_META.capability.color}
      active={creation.activeKey === capabilityKey}
      value={creation.value}
      pending={creation.pending}
      error={creation.activeKey === capabilityKey ? creation.error : undefined}
      onStart={() => creation.begin(2, topic.goal.id!)}
      onChange={creation.change}
      onSubmit={creation.submit}
      onCancel={creation.cancel}
    />
  );

  return (
    <div ref={containerRef} className="flex flex-col px-3 pb-4">
      <div className="relative">
        <div ref={scrollRef} className="scrollbar-none overflow-x-auto pb-2">
          {/* Keyed by the focus so every navigation remounts the tiers — the connectors
              redraw and the children replay their entrance, while surviving boxes FLIP. */}
          <div
            key={`tree-${topic.goal.id}-${focused?.goal.id ?? "none"}`}
            className="mx-auto flex w-max min-w-full flex-col items-center"
            style={
              focused
                ? { paddingLeft: leafLeftOverhang, paddingRight: leafRightOverhang }
                : undefined
            }
          >
            {/* Rises to the topic row above; in a drilled-in state it leads to the focused box. */}
            <Connector
              childWidths={siblingWidths}
              color={COMPETENCY_ROLE_META.topic.color}
              focusedIndex={focused ? focusedSubIndex : undefined}
            />
            <CreationRow reserve={capabilityReserve} ghost={capabilityGhost}>
              {topic.children.map((child, i) => {
                const isCapability = child.role === "capability";
                const isFocused = focused != null && child.goal.id === focused.goal.id;
                return (
                  // Column cell: the box plus, while nothing is focused and a tier waits beneath
                  // a capability, the mini leaf indicator branching off below it. Stub width <
                  // box width, so the cell keeps the box's footprint and the connector stays aligned.
                  <div
                    key={child.goal.id}
                    className="comp-pop flex flex-col items-center"
                    style={{ animationDelay: `${180 + i * 30}ms` }}
                  >
                    <Box
                      node={child}
                      active={isFocused}
                      expandable={isCapability && child.children.length > 0}
                      onClick={() =>
                        isCapability
                          ? pickCapability(child.goal.id!)
                          : onOpenDetail(child)
                      }
                      onDetails={isCapability ? () => onOpenDetail(child) : undefined}
                      actions={actions}
                      dimmed={focused != null && !isFocused}
                      compact={focused != null && !isFocused}
                      clampText={focused != null && !isFocused}
                      sequenceLabel={`${sequence}.${i + 1}`}
                      levelFlag={flags.get(child.goal.id!)}
                      title={
                        isCapability
                          ? isFocused
                            ? "Fold"
                            : "Unfold"
                          : "View goal details"
                      }
                    />
                    {!focused && isCapability && child.children.length > 0 && (
                      <LeafStub count={child.children.length} />
                    )}
                  </div>
                );
              })}
            </CreationRow>
            {focused && (
              // The active node sits at a known fixed position in the sibling row. Moving this
              // whole leaf branch by that same offset makes its connector originate at the
              // focused node instead of the row centre.
              <div
                className="relative flex flex-col items-center"
                style={{ left: focusedSubOffset }}
              >
                <Connector
                  childWidths={leafWidths}
                  color={COMPETENCY_ROLE_META.skill.color}
                />
                <CreationRow
                  reserve={leafReserve}
                  ghost={
                    <CreationGhost
                      label="New sub-skill"
                      color={COMPETENCY_ROLE_META.skill.color}
                      active={creation.activeKey === skillKey}
                      value={creation.value}
                      pending={creation.pending}
                      error={creation.activeKey === skillKey ? creation.error : undefined}
                      onStart={() => creation.begin(3, focused.goal.id!)}
                      onChange={creation.change}
                      onSubmit={creation.submit}
                      onCancel={creation.cancel}
                    />
                  }
                >
                  {focused.children.map((leaf, i) => (
                    // The leaves pop in after the focused node has slid into place.
                    <div
                      key={leaf.goal.id}
                      className="comp-pop"
                      style={{ animationDelay: `${240 + i * 30}ms` }}
                    >
                      <Box
                        node={leaf}
                        active={false}
                        expandable={false}
                        onClick={() => onOpenDetail(leaf)}
                        actions={actions}
                        leaf
                        sequenceLabel={`${sequence}.${focusedSubIndex + 1}.${i + 1}`}
                        levelFlag={flags.get(leaf.goal.id!)}
                      />
                    </div>
                  ))}
                </CreationRow>
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

/**
 * A row of boxes with its creation control beside them. The control lives in a reserve kept on
 * both sides, so the boxes stay centred exactly under the connector drawn for them alone.
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
  return (
    <div
      className="relative flex justify-center gap-3"
      style={{ paddingLeft: reserve, paddingRight: reserve }}
    >
      {children}
      {ghost && reserve > 0 && (
        <div
          className="absolute top-1/2 right-0 flex -translate-y-1/2 justify-start"
          style={{ width: reserve - GAP }}
        >
          {ghost}
        </div>
      )}
    </div>
  );
}

/** Edge fade with a scroll arrow, shown only on a side the map overflows. */
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
      className={`pointer-events-none absolute inset-y-0 z-10 flex w-14 items-center ${
        left ? "left-0 justify-start pl-1" : "right-0 justify-end pr-1"
      }`}
      style={{
        background: `linear-gradient(to ${left ? "right" : "left"}, var(--hestia-bg), color-mix(in srgb, var(--hestia-bg) 80%, transparent), transparent)`,
      }}
    >
      <button
        type="button"
        aria-label={`Scroll topic map ${side}`}
        className="pointer-events-auto rounded-full border border-hestia-border bg-hestia-bg/90 p-1 text-hestia-text-muted shadow-sm transition hover:bg-hestia-surface hover:text-hestia-text"
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

/** The edit actions shared by every box; approving stays a list-view concern. */
type GoalActions = {
  onEdit: (goal: LearningGoal) => void;
  onDelete: (goal: LearningGoal) => void;
};

/** A readable capability/skill/knowledge rectangle. Branch boxes carry a child count and an
 * unfold chevron; every box carries its actions top-right, which fade in on hover. Clicking the
 * body folds or unfolds a capability or opens the goal detail — the classification (Bloom / SOLO
 * / kind / source) lives in that detail modal. It is a div (not a button) so the action buttons
 * can nest. */
function Box({
  node,
  active,
  expandable,
  onClick,
  onDetails,
  actions,
  dimmed = false,
  compact = false,
  leaf = false,
  clampText = false,
  title,
  sequenceLabel,
  levelFlag,
}: {
  node: CompetencyNode;
  /** The box is the focused, unfolded node. */
  active: boolean;
  expandable: boolean;
  onClick: () => void;
  /** Opens the goal detail from an action, for boxes whose click unfolds instead. */
  onDetails?: () => void;
  actions: GoalActions;
  /** An unfocused sibling in the visible row. */
  dimmed?: boolean;
  /** Shrinks a dimmed sibling to keep focused context rows compact. */
  compact?: boolean;
  /** Leaf-row width (w-60). */
  leaf?: boolean;
  /** Keeps sibling context compact without truncating the focused node. */
  clampText?: boolean;
  /** Overrides the default branch/detail tooltip. */
  title?: string;
  /** Hierarchical lecture-order label, e.g. "2.3". */
  sequenceLabel?: string;
  /** Why this sub-skill's exercise level looks abnormal against its topic's lectures, if it does. */
  levelFlag?: string;
}) {
  const meta = COMPETENCY_ROLE_META[node.role];
  const isGap = node.role === "gap";
  const childCount = node.children.length;
  return (
    <div
      role="button"
      tabIndex={0}
      data-goal-id={node.goal.id}
      onClick={onClick}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onClick();
        }
      }}
      title={title ?? (!active && expandable ? "Unfold" : "View goal details")}
      className={`group relative flex cursor-pointer flex-col gap-1.5 rounded-lg border-[1.5px] p-3 text-left transition ${
        leaf ? "w-60 shrink-0" : compact ? "w-40 shrink-0" : "w-56 shrink-0"
      } ${
        isGap
          ? "border-hestia-danger/40 bg-[color-mix(in_srgb,var(--hestia-danger)_8%,var(--hestia-surface))] hover:border-hestia-danger"
          : node.role === "knowledge"
            ? "border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_2.5%,var(--hestia-surface))] hover:border-hestia-primary hover:bg-hestia-bg"
          : "border-hestia-border bg-hestia-surface hover:border-hestia-primary hover:bg-hestia-bg"
      } ${dimmed ? "opacity-60 hover:opacity-100" : ""}`}
      style={{
        // The whole outline carries the role colour, so the tier reads without a shadow or rail.
        ...(isGap ? {} : { borderColor: meta.color }),
        // The focused box keeps a quiet role tint while its row siblings remain subdued context.
        ...(active
          ? {
              backgroundColor: `color-mix(in srgb, ${meta.color} 12%, var(--hestia-surface))`,
            }
          : {}),
      }}
    >
      {/* Header: the role badge on the left, the actions pinned right. */}
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-1.5">
          <RoleBadge role={node.role} />
          {node.goal.creationProvenance === "WIZARD_AI_SUBTREE" && (
            <AiInferredBadge compact />
          )}
          {node.goal.creationProvenance === "USER_CREATED" && <ManualBadge />}
        </div>
        <div className="flex items-center gap-0.5">
          {onDetails && (
            <BoxAction
              label="Open details"
              tip="Open this goal's details: its sources, levels and wording."
              tipBelow
              onClick={onDetails}
              className="text-hestia-text-muted hover:bg-hestia-primary-muted hover:text-hestia-text"
            >
              <svg
                viewBox="0 0 20 20"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
                className="h-4 w-4"
              >
                <circle cx="10" cy="10" r="7" />
                <path d="M10 9v4.5M10 6.5v.01" />
              </svg>
            </BoxAction>
          )}
          <BoxAction
            label="Edit goal"
            tip="Edit this goal's wording and its Bloom / SOLO level."
            tipBelow
            onClick={() => actions.onEdit(node.goal)}
            className="text-hestia-text-muted hover:bg-hestia-primary-muted hover:text-hestia-text"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4"
            >
              <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
            </svg>
          </BoxAction>
          <BoxAction
            label="Delete goal"
            tip="Delete this goal permanently."
            tipBelow
            onClick={() => actions.onDelete(node.goal)}
            className="text-hestia-text-muted hover:bg-hestia-danger hover:text-hestia-on-danger"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="h-4 w-4"
            >
              <path d="M4 6h12M8 6V4h4v2M6 6l1 10h6l1-10" />
            </svg>
          </BoxAction>
        </div>
      </div>
      <p
        className={`text-sm font-medium leading-snug ${
          isGap ? "text-hestia-danger" : "text-hestia-text"
        } ${clampText ? "line-clamp-3" : ""}`}
      >
        {sequenceLabel != null && <span className="tabular-nums">{sequenceLabel} </span>}
        {node.goal.shortLabel ?? node.goal.text}
      </p>
      <div className="mt-auto flex items-center gap-1 pt-1">
        <CoverageBadge goal={node.goal} flag={levelFlag} />
        {expandable && (
          <span className="flex items-center gap-1 text-xs text-hestia-text-muted">
            <span className="tabular-nums">
              {childCount} item
              {childCount === 1 ? "" : "s"}
            </span>
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
          </span>
        )}
      </div>
    </div>
  );
}

/**
 * Mini leaf indicator under an unfocused capability: stub lines branching into a few dots,
 * hinting that another tier unfolds beneath it. The count is suggestive (capped at three); the
 * exact number already sits in the box's "N items" line.
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

/**
 * Icon action inside a box, mirroring the list view's CardAction: stops propagation so it doesn't
 * also unfold/open the box, fades in on box hover (unless `alwaysVisible`), and reveals a tooltip
 * describing what it does on its own hover.
 */
function BoxAction({
  label,
  tip,
  onClick,
  className,
  alwaysVisible,
  tipBelow,
  children,
}: {
  label: string;
  tip: string;
  onClick: () => void;
  className: string;
  alwaysVisible?: boolean;
  tipBelow?: boolean;
  children: ReactNode;
}) {
  return (
    <span className="group/tip relative inline-flex">
      <button
        type="button"
        title={label}
        aria-label={label}
        onClick={(e) => {
          e.stopPropagation();
          onClick();
        }}
        className={`flex h-7 w-7 items-center justify-center rounded-md transition focus-visible:opacity-100 ${
          alwaysVisible ? "" : "opacity-0 group-hover:opacity-100"
        } ${className}`}
      >
        {children}
      </button>
      <span
        role="tooltip"
        className={`pointer-events-none absolute right-0 z-30 hidden w-44 rounded-lg border border-hestia-border border-l-[3px] border-l-hestia-primary bg-hestia-surface p-2 text-left text-xs font-normal normal-case leading-snug text-hestia-text shadow-lg group-hover/tip:block ${
          tipBelow ? "top-full mt-1.5" : "bottom-full mb-1.5"
        }`}
      >
        {tip}
      </span>
    </span>
  );
}
