import { useLayoutEffect, useRef } from "react";

/**
 * Gives each section its own scroll offset, so a single persistent scroll
 * container doesn't share one offset across sections. Memory lives in a ref for
 * the life of the component, so it is deliberately discarded once the user
 * leaves the view (Edit / Grading mode).
 */
export function useSectionScrollMemory<T extends HTMLElement = HTMLDivElement>(
  sectionId: string,
) {
  const ref = useRef<T>(null);
  const offsets = useRef<Map<string, number>>(new Map());

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    // Restore before paint, so the section never flashes at the old offset.
    el.scrollTo({ top: offsets.current.get(sectionId) ?? 0 });
    const onScroll = () => offsets.current.set(sectionId, el.scrollTop);
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => el.removeEventListener("scroll", onScroll);
  }, [sectionId]);

  return ref;
}
