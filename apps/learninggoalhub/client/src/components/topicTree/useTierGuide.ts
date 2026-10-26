import { useEffect, useState } from "react";

/** Set once a reader dismissed a course's first-visit hint on how to read the table. */
const tierGuideSeenKey = (courseId: number) => `learninggoalhub.tierGuide.seen.${courseId}`;

/**
 * The strip explaining the tiers shows on a reader's first visit to a course; once dismissed it
 * folds into the toolbar's "How to read this" button, which flashes briefly to show where it went.
 */
export function useTierGuide(courseId: number) {
  const [open, setOpen] = useState(false);
  const [seen, setSeen] = useState(() => {
    try {
      return localStorage.getItem(tierGuideSeenKey(courseId)) != null;
    } catch {
      return false;
    }
  });
  const [flash, setFlash] = useState(false);
  const dismiss = () => {
    setSeen(true);
    setFlash(true);
    try {
      localStorage.setItem(tierGuideSeenKey(courseId), "1");
    } catch {
      // Without storage the strip simply returns on the next visit.
    }
  };
  useEffect(() => {
    if (!flash) return;
    const timer = window.setTimeout(() => setFlash(false), 1600);
    return () => window.clearTimeout(timer);
  }, [flash]);
  return { open, setOpen, seen, flash, dismiss };
}
