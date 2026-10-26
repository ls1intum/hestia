import { useRef, type ReactNode } from "react";
import { COLUMNS } from "./columns.ts";

export function HeaderCell({
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
