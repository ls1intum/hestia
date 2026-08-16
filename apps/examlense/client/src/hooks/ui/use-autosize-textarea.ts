import { useCallback, useLayoutEffect, useRef } from "react";

type AutosizeRef<T> = ((node: T | null) => void) & { readonly current: T | null };

export function useAutosizeTextarea<T extends HTMLTextAreaElement>(
  value: string,
): AutosizeRef<T> {
  const ref = useRef<T | null>(null);
  const manuallyResized = useRef(false);

  const resize = useCallback(() => {
    const el = ref.current;
    if (!el || manuallyResized.current) return;
    el.style.height = "auto";
    el.style.height = `${el.scrollHeight}px`;
  }, []);

  const onPointerDown = useCallback((e: PointerEvent) => {
    const el = ref.current;
    if (!el) return;
    // Grabbing the bottom-right resize handle marks the size as user-owned.
    const rect = el.getBoundingClientRect();
    if (e.clientX > rect.right - 16 && e.clientY > rect.bottom - 16) {
      manuallyResized.current = true;
    }
  }, []);

  // Callback ref: resize as soon as the textarea attaches (e.g. when a card
  // switches from preview to edit mode), not only when `value` changes.
  const setRef = useCallback<(node: T | null) => void>(
    (node) => {
      if (ref.current) {
        ref.current.removeEventListener("pointerdown", onPointerDown);
      }
      ref.current = node;
      if (node) {
        node.addEventListener("pointerdown", onPointerDown);
        resize();
      }
    },
    [onPointerDown, resize],
  ) as AutosizeRef<T>;

  if (!Object.getOwnPropertyDescriptor(setRef, "current")) {
    Object.defineProperty(setRef, "current", {
      get: () => ref.current,
      configurable: true,
    });
  }

  useLayoutEffect(() => {
    resize();
  }, [value, resize]);

  return setRef;
}
