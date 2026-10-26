import { useLayoutEffect, useRef, useState, type CSSProperties, type ReactNode } from "react";
import CompetencyCreationField from "../CompetencyCreationField.tsx";
import { COMPETENCY_ROLE_META, displayedGoalLabel, type CompetencyNode } from "../../lib/goals.ts";
import { GAP, MINI_LIMIT } from "./layout.ts";
import { LeafStub } from "./Box.tsx";

/**
 * What a closed box holds, hanging beneath it: its first children as mini boxes on a thin line,
 * each opening the box with that child highlighted, then a "+N more" pill that opens the box. A dimmed
 * context box only hints with stub dots, so the focused branch stays the thing to read.
 */
export function ChildHint({
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
  if (node.children.length === 0 || node.role === "knowledge") return null;
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
export function CreationRow({
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
 * Creation control beside a row: a quiet dashed pill that only takes the role colour on hover, and
 * the creation field once started.
 */
export function CreationGhost({
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
