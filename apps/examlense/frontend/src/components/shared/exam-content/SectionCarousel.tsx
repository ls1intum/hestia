import { useEffect, useMemo, useRef } from "react";

export interface CarouselSlide {
  /** Stable id used by sidebar selection, hash sync, and arrow-key nav. */
  id: string;
  /** Rendered slide body. */
  content: React.ReactNode;
}

interface Props {
  slides: CarouselSlide[];
  currentId: string;
  onChange: (id: string) => void;
}

/**
 * Single-slide-at-a-time container. Navigation only — chromeless. The visible
 * slide is driven by `currentId`; arrow keys move between slides and the URL
 * hash is kept in sync for deep links and external (sidebar / tabs) selection.
 */
export const SectionCarousel = ({ slides, currentId, onChange }: Props) => {
  const slideBodyRef = useRef<HTMLDivElement>(null);
  const didMountRef = useRef(false);

  const index = useMemo(() => {
    const i = slides.findIndex((s) => s.id === currentId);
    return i < 0 ? 0 : i;
  }, [slides, currentId]);

  const current = slides[index];
  const prev = slides[index - 1];
  const next = slides[index + 1];
  const currentSlideId = current?.id;

  // Move focus to the slide body whenever the active slide changes so
  // keyboard and screen-reader users land on the new content. Skip the
  // initial mount — on a fresh page load the user is reading, not
  // navigating, and a keyboard-driven reload (Cmd+R) can cause the
  // browser to treat programmatic focus as keyboard focus and paint a
  // :focus-visible ring around the slide.
  useEffect(() => {
    if (!currentSlideId) return;
    if (!didMountRef.current) {
      didMountRef.current = true;
      return;
    }
    slideBodyRef.current?.focus({ preventScroll: true });
  }, [currentSlideId]);

  // Keep URL hash in sync with the visible slide (so external links and the
  // sidebar's goToSection -> hash flow both land on the right card).
  useEffect(() => {
    if (!current) return;
    const desired = `#${current.id}`;
    if (window.location.hash !== desired) {
      window.history.replaceState(null, "", desired);
      window.dispatchEvent(new HashChangeEvent("hashchange"));
    }
  }, [current]);

  // React to external hash changes (sidebar clicks, deep links).
  useEffect(() => {
    const onHash = () => {
      const id = window.location.hash.replace(/^#/, "");
      if (!id) return;
      if (slides.some((s) => s.id === id) && id !== currentId) onChange(id);
    };
    window.addEventListener("hashchange", onHash);
    onHash();
    return () => window.removeEventListener("hashchange", onHash);
  }, [slides, currentId, onChange]);

  // Arrow-key navigation (ignored when typing in inputs/textareas).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tgt = e.target as HTMLElement | null;
      if (
        tgt &&
        (tgt.tagName === "INPUT" ||
          tgt.tagName === "TEXTAREA" ||
          tgt.isContentEditable)
      ) {
        return;
      }
      if (e.key === "ArrowRight" && next) {
        e.preventDefault();
        onChange(next.id);
      } else if (e.key === "ArrowLeft" && prev) {
        e.preventDefault();
        onChange(prev.id);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [prev, next, onChange]);

  if (!current) return null;

  const focusRing =
    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary focus-visible:ring-offset-2 focus-visible:ring-offset-hestia-bg";

  return (
    <div
      key={current.id}
      ref={slideBodyRef}
      tabIndex={-1}
      aria-live="polite"
      aria-atomic="false"
      className={"animate-fade-in rounded-hestia-md " + focusRing}
    >
      {current.content}
    </div>
  );
};
