import { useEffect, useRef, useState } from "react";

/**
 * Hook: persist per-item (task or context block) collapsed state in
 * localStorage, scoped by exam id. Provides toggling of individual items, of
 * all items in a given subset, and a global expand-all/collapse-all.
 */
export const useItemCollapseState = (examId: string | undefined) => {
  const storageKey = examId ? `examedit:items-collapsed:${examId}` : null;
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>(() => {
    if (typeof window === "undefined" || !storageKey) return {};
    try {
      const raw = window.localStorage.getItem(storageKey);
      return raw ? (JSON.parse(raw) as Record<string, boolean>) : {};
    } catch {
      return {};
    }
  });
  const hydratedKeyRef = useRef<string | null>(
    typeof window !== "undefined" && storageKey ? storageKey : null,
  );

  useEffect(() => {
    if (typeof window === "undefined" || !storageKey) return;
    if (hydratedKeyRef.current === storageKey) return;
    try {
      const raw = window.localStorage.getItem(storageKey);
      setCollapsed(raw ? (JSON.parse(raw) as Record<string, boolean>) : {});
    } catch {
      setCollapsed({});
    }
    hydratedKeyRef.current = storageKey;
  }, [storageKey]);

  useEffect(() => {
    if (typeof window === "undefined" || !storageKey) return;
    if (hydratedKeyRef.current !== storageKey) return;
    try {
      window.localStorage.setItem(storageKey, JSON.stringify(collapsed));
    } catch {
      /* ignore quota errors */
    }
  }, [collapsed, storageKey]);

  const isCollapsed = (id: string) => !!collapsed[id];

  const toggle = (id: string) =>
    setCollapsed((prev) => ({ ...prev, [id]: !prev[id] }));

  const allCollapsedIn = (ids: string[]) =>
    ids.length > 0 && ids.every((id) => collapsed[id]);

  const setManyCollapsed = (ids: string[], value: boolean) =>
    setCollapsed((prev) => {
      const next = { ...prev };
      for (const id of ids) {
        if (value) next[id] = true;
        else delete next[id];
      }
      return next;
    });

  return { isCollapsed, toggle, allCollapsedIn, setManyCollapsed };
};
