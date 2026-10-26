import { useEffect, useRef, type RefObject } from "react";

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Keeps Tab inside an open modal, so keyboard focus cannot wander to the page behind it, and hands
 * focus back to whatever opened the modal once it closes. A modal opened on top of this one, inside
 * its DOM, traps on its own. Focus that sits in a portaled popover is left alone.
 */
export function useFocusTrap(ref: RefObject<HTMLElement | null>, active = true) {
  // Read during the render that opens the modal, before an autoFocus inside it moves focus.
  const opener = useRef<Element | null>(null);
  const wasActive = useRef(false);
  if (active && !wasActive.current) opener.current = document.activeElement;
  wasActive.current = active;

  useEffect(() => {
    const dialog = ref.current;
    if (!active || !dialog) return;
    const returnTo = opener.current;
    if (!dialog.contains(document.activeElement)) dialog.focus();

    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Tab") return;
      if (dialog.querySelector('[aria-modal="true"]')) return;
      const items = [...dialog.querySelectorAll<HTMLElement>(FOCUSABLE)].filter(
        (el) => el.getClientRects().length > 0,
      );
      const current = document.activeElement;
      const first = items[0];
      const last = items[items.length - 1];
      const outside = current === document.body || current === dialog;
      if (!first) {
        e.preventDefault();
      } else if (e.shiftKey && (current === first || outside)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (current === last || outside)) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      if (returnTo instanceof HTMLElement) returnTo.focus();
    };
  }, [ref, active]);
}
