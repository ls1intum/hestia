import { useLayoutEffect, useRef } from "react";

/**
 * Remembers the scroll offset of each section and restores it when the user
 * returns to that section, while starting a not-yet-visited section at the
 * top. This keeps a single persistent scroll container from sharing one offset
 * across sections. Memory lives in a ref for the life of the component, so it
 * is discarded once the user leaves the view (Edit / Grading mode).
 *
 * Returns a ref to attach to the scroll container; `sectionId` is the active
 * section slug that changes when the user switches sections.
 */
export function useSectionScrollMemory<T extends HTMLElement = HTMLDivElement>(
  sectionId: string,
) {
  const ref = useRef<T>(null);
  const offsets = useRef<Map<string, number>>(new Map());

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    // Restore this section's remembered offset before paint (top if unseen).
    el.scrollTo({ top: offsets.current.get(sectionId) ?? 0 });
    // Then keep it up to date while the user scrolls this section.
    const onScroll = () => offsets.current.set(sectionId, el.scrollTop);
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => el.removeEventListener("scroll", onScroll);
  }, [sectionId]);

  return ref;
}
