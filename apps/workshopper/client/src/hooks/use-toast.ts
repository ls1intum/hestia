// Minimal toast hook compatible with the Lovable component usage pattern.
// Uses a simple React state-based approach (no external library needed).

import { useState, useCallback } from "react";

export type ToastVariant = "default" | "destructive";

export interface Toast {
  id: string;
  title: string;
  description?: string;
  variant?: ToastVariant;
}

export interface ToastInput {
  title: string;
  description?: string;
  variant?: ToastVariant;
}

// Global singleton store so `toast()` works outside of React components
type Listener = (toasts: Toast[]) => void;
let toasts: Toast[] = [];
const listeners: Set<Listener> = new Set();

function notify() {
  listeners.forEach((l) => l([...toasts]));
}

let idCounter = 0;

export function toast(input: ToastInput) {
  const id = String(++idCounter);
  const t: Toast = { id, ...input };
  toasts = [t, ...toasts].slice(0, 5);
  notify();
  // Auto-dismiss after 4 s
  setTimeout(() => {
    toasts = toasts.filter((x) => x.id !== id);
    notify();
  }, 4000);
}

export function useToast() {
  const [list, setList] = useState<Toast[]>([...toasts]);

  const subscribe = useCallback((l: Listener) => {
    listeners.add(l);
    return () => listeners.delete(l);
  }, []);

  useState(() => {
    const unsub = subscribe(setList);
    return unsub;
  });

  const dismiss = useCallback((id: string) => {
    toasts = toasts.filter((t) => t.id !== id);
    notify();
  }, []);

  return { toasts: list, toast, dismiss };
}
