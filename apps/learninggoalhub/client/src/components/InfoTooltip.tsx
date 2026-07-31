import { useEffect, useId, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";

/** Breathing room kept between the card and the viewport edges. */
const EDGE = 8;
/** Below this much room underneath the trigger the card opens upwards instead. */
const MIN_ROOM = 140;
/** Preferred card width; narrower only when the viewport itself is. */
const WIDTH = 288;

type Placement = {
  style: {
    top?: number;
    bottom?: number;
    left: number;
    width: number;
    maxHeight: number;
  };
  flipped: boolean;
};

/** Centres the card under (or above) the trigger and clamps it into the viewport. */
function place(anchor: DOMRect): Placement {
  const { innerWidth: vw, innerHeight: vh } = window;
  const below = vh - anchor.bottom - EDGE;
  const above = anchor.top - EDGE;
  const flipped = below < MIN_ROOM && above > below;
  const width = Math.min(WIDTH, vw - 2 * EDGE);
  const left = Math.min(
    Math.max(EDGE, anchor.left + anchor.width / 2 - width / 2),
    vw - EDGE - width,
  );
  return {
    flipped,
    style: {
      ...(flipped ? { bottom: vh - anchor.top + 6 } : { top: anchor.bottom + 6 }),
      left,
      width,
      maxHeight: Math.max(MIN_ROOM, flipped ? above : below),
    },
  };
}

/**
 * Explanatory hover card in the HESTIA surface idiom, shown on hover and on keyboard focus.
 * The card is portalled to the body (the tree-grid header lives in a scroll container that would
 * otherwise clip it) and re-placed on scroll, and it never takes pointer events, so it can hover
 * over a clickable row without swallowing the click.
 */
export default function InfoTooltip({
  content,
  className,
  children,
}: {
  content: ReactNode;
  /** Classes for the inline trigger wrapper. */
  className?: string;
  children: ReactNode;
}) {
  const id = useId();
  const triggerRef = useRef<HTMLSpanElement>(null);
  const [open, setOpen] = useState(false);
  const [placement, setPlacement] = useState<Placement | null>(null);

  useLayoutEffect(() => {
    if (!open) return;
    const measure = () => {
      const anchor = triggerRef.current;
      if (anchor) setPlacement(place(anchor.getBoundingClientRect()));
    };
    measure();
    window.addEventListener("scroll", measure, true);
    window.addEventListener("resize", measure);
    return () => {
      window.removeEventListener("scroll", measure, true);
      window.removeEventListener("resize", measure);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  return (
    <>
      <span
        ref={triggerRef}
        aria-describedby={open ? id : undefined}
        onMouseEnter={() => setOpen(true)}
        onMouseLeave={() => setOpen(false)}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        className={className}
      >
        {children}
      </span>
      {open &&
        placement != null &&
        createPortal(
          <div
            id={id}
            role="tooltip"
            style={placement.style}
            className={`comp-unfold pointer-events-none fixed z-50 overflow-hidden rounded-lg border border-hestia-border bg-hestia-surface p-3 font-normal normal-case tracking-normal shadow-lg ${
              placement.flipped ? "origin-bottom" : "origin-top"
            }`}
          >
            {content}
          </div>,
          document.body,
        )}
    </>
  );
}
