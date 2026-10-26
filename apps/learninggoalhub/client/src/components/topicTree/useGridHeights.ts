import { useLayoutEffect, useState, type RefObject } from "react";

// The grid's scroll area: it reaches the bottom of the window bar this gap, and never shrinks
// below a few rows on a short window — there the page scrolls instead.
const SCROLLER_BOTTOM_GAP = 32;
const MIN_SCROLLER_HEIGHT = 320;

/**
 * The grid ends at the bottom of the window, whatever stands above it — page title, toolbar, the
 * tier strip, a row of filter chips. A fixed slice of the viewport had to leave room for the
 * tallest of those, so it wasted rows whenever they were absent.
 */
export function useScrollerHeight(scrollerRef: RefObject<HTMLDivElement>) {
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
  }, [scrollerRef]);
  return scrollerHeight;
}

/**
 * The column header's height, which the open topic row needs to stick right under it. Measured
 * again whenever the header itself appears, which `present` signals.
 */
export function useHeaderHeight(headerRef: RefObject<HTMLDivElement>, present: boolean) {
  const [headerHeight, setHeaderHeight] = useState(0);
  useLayoutEffect(() => {
    const header = headerRef.current;
    if (!header) return;
    const measure = () => setHeaderHeight(header.offsetHeight);
    const observer = new ResizeObserver(measure);
    observer.observe(header);
    measure();
    return () => observer.disconnect();
  }, [headerRef, present]);
  return headerHeight;
}

/**
 * Whether columns lie beyond the grid's right edge. On a narrow window the table scrolls sideways,
 * and without a cue the cut-off columns look like the end of the table.
 */
export function useOverflowsRight(scrollerRef: RefObject<HTMLDivElement>) {
  const [overflows, setOverflows] = useState(false);
  useLayoutEffect(() => {
    const scroller = scrollerRef.current;
    if (!scroller) return;
    // One pixel of slack: fractional widths can leave a sub-pixel scroll range at the very end.
    const measure = () =>
      setOverflows(scroller.scrollLeft + scroller.clientWidth < scroller.scrollWidth - 1);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(scroller);
    if (scroller.firstElementChild) observer.observe(scroller.firstElementChild);
    scroller.addEventListener("scroll", measure, { passive: true });
    return () => {
      observer.disconnect();
      scroller.removeEventListener("scroll", measure);
    };
  }, [scrollerRef]);
  return overflows;
}
