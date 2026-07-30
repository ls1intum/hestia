import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

/** Breathing room kept between the popover and the viewport edges. */
const EDGE = 8;
/** Below this much room underneath the trigger the menu opens upwards instead. */
const MIN_ROOM = 160;
/** `min-w-44` in pixels — clamping narrower than this only makes the panel overflow anyway. */
const MIN_WIDTH = 176;

type Placement = {
  style: {
    top?: number;
    bottom?: number;
    left?: number;
    right?: number;
    maxWidth: number;
    maxHeight: number;
  };
  flipped: boolean;
};

/**
 * Places the panel against the anchor box the popover used to be absolutely positioned in, but in
 * viewport coordinates: 2px below it, inset 4px from the aligned edge, clamped to the viewport and
 * flipped above when there is no usable room below.
 */
function place(anchor: DOMRect, alignRight: boolean): Placement {
  const { innerWidth: vw, innerHeight: vh } = window;
  const below = vh - anchor.bottom - EDGE;
  const above = anchor.top - EDGE;
  const flipped = below < MIN_ROOM && above > below;
  // Hug the aligned edge of the anchor, but keep at least a full panel width on screen.
  const inset = Math.min(
    alignRight ? Math.max(EDGE, vw - anchor.right + 4) : Math.max(EDGE, anchor.left + 4),
    Math.max(EDGE, vw - EDGE - MIN_WIDTH),
  );
  return {
    flipped,
    style: {
      ...(flipped ? { bottom: vh - anchor.top + 2 } : { top: anchor.bottom + 2 }),
      ...(alignRight ? { right: inset } : { left: inset }),
      maxWidth: Math.max(MIN_WIDTH, vw - inset - EDGE),
      maxHeight: Math.max(MIN_ROOM, flipped ? above : below),
    },
  };
}

/**
 * Excel-AutoFilter-style multi-select checkbox popover, anchored below its trigger. Shared by the
 * competency tree-grid (per-column funnel) and the list view's filter bar so both filter the same
 * way. Must be rendered as a child of the same `relative` element that holds the trigger button:
 * that element is both the anchor the panel is positioned against and the outside-click guard's
 * "inside" (the trigger toggles the popover itself). The panel itself is portalled to the body,
 * because the tree-grid header sits in a scroll container that would otherwise clip it; only a
 * hidden marker stays behind to point at the anchor.
 */
export default function FilterPopover({
  options,
  selected,
  display,
  alignRight,
  onToggle,
  onClear,
  onClose,
}: {
  options: string[];
  selected: Set<string>;
  display: (value: string) => string;
  alignRight?: boolean;
  onToggle: (value: string) => void;
  onClear: () => void;
  onClose: () => void;
}) {
  const markerRef = useRef<HTMLSpanElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const [placement, setPlacement] = useState<Placement | null>(null);

  // The anchor moves with every scroll of any ancestor (capture phase catches the inner scroll
  // container too), so the fixed panel is re-placed rather than measured once.
  useLayoutEffect(() => {
    const measure = () => {
      const anchor = markerRef.current?.parentElement;
      if (anchor) setPlacement(place(anchor.getBoundingClientRect(), !!alignRight));
    };
    measure();
    window.addEventListener("scroll", measure, true);
    window.addEventListener("resize", measure);
    return () => {
      window.removeEventListener("scroll", measure, true);
      window.removeEventListener("resize", measure);
    };
  }, [alignRight]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    const onDown = (e: MouseEvent) => {
      // The trigger button toggles the popover itself; only close on truly-outside clicks.
      const anchor = markerRef.current?.parentElement;
      if (
        anchor &&
        !anchor.contains(e.target as Node) &&
        !panelRef.current?.contains(e.target as Node)
      )
        onClose();
    };
    window.addEventListener("keydown", onKey);
    window.addEventListener("mousedown", onDown);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("mousedown", onDown);
    };
  }, [onClose]);

  return (
    <>
      {/* Not rendered: it only marks where the popover belongs so the panel can find its anchor. */}
      <span ref={markerRef} className="hidden" aria-hidden="true" />
      {placement != null &&
        createPortal(
          <div
            ref={panelRef}
            style={placement.style}
            className={`comp-unfold fixed z-40 flex min-w-44 flex-col rounded-lg border border-hestia-border bg-hestia-surface p-1.5 font-normal normal-case tracking-normal shadow-lg ${
              placement.flipped ? "origin-bottom" : "origin-top"
            }`}
          >
            <div className="min-h-0 flex-1 overflow-y-auto">
              {options.map((value) => (
                <label
                  key={value}
                  className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1 text-sm text-hestia-text hover:bg-hestia-text/5"
                >
                  <input
                    type="checkbox"
                    checked={selected.has(value)}
                    onChange={() => onToggle(value)}
                    className="h-3.5 w-3.5 shrink-0 accent-hestia-primary"
                  />
                  {display(value)}
                </label>
              ))}
            </div>
            <div className="mt-1 flex justify-between gap-2 border-t border-hestia-border px-2 pb-0.5 pt-1.5">
              <button
                type="button"
                onClick={onClear}
                className="text-xs font-semibold text-hestia-primary transition hover:text-hestia-primary-hover"
              >
                Clear
              </button>
              <button
                type="button"
                onClick={onClose}
                className="text-xs font-semibold text-hestia-primary transition hover:text-hestia-primary-hover"
              >
                Done
              </button>
            </div>
          </div>,
          document.body,
        )}
    </>
  );
}
