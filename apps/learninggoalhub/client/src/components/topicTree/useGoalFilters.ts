import { useCallback, useMemo, useState } from "react";
import { COLUMNS } from "./columns.ts";
import {
  BLOOM_ORDER,
  COVERAGE_ORDER,
  FILTER_LABELS,
  KIND_ORDER,
  NO_IDS,
  ROLE_ORDER,
  SOLO_ORDER,
  displayValue,
  valuesOf,
  type FilterKey,
  type Row,
} from "./model.ts";

const noFilters = (): Record<FilterKey, Set<string>> => ({
  role: new Set(),
  kind: new Set(),
  bloom: new Set(),
  solo: new Set(),
  document: new Set(),
  session: new Set(),
  coverage: new Set(),
});

/** One active filter or the search, as a removable chip above the grid. */
export type FilterChip = {
  label: string;
  value: string;
  count: number;
  onRemove: () => void;
};

/**
 * The search and the column filters, and the list of matches they leave: the matching rows plus
 * their non-matching ancestors as dimmed context. Also the fold state of that list, which lasts
 * only while the same filter holds.
 */
export function useGoalFilters(rows: Row[], byId: Map<number, Row>) {
  const [search, setSearch] = useState("");
  const [filters, setFilters] = useState<Record<FilterKey, Set<string>>>(noFilters);

  const filtering =
    search.trim() !== "" || Object.values(filters).some((s) => s.size > 0);

  // Whether a row survives the search and every filter, optionally ignoring one key: the popover's
  // per-value counts ask what one column's filter would leave once the others have had their say.
  const matchesRow = useCallback(
    (row: Row, ignore: FilterKey | null = null): boolean => {
      const needle = search.trim().toLowerCase();
      if (
        needle &&
        ![row.goal.shortLabel, row.goal.text].some((value) =>
          (value ?? "").toLowerCase().includes(needle),
        )
      )
        return false;
      for (const key of Object.keys(filters) as FilterKey[]) {
        if (key === ignore) continue;
        const set = filters[key];
        if (set.size > 0 && !valuesOf(row, key).some((v) => set.has(v)))
          return false;
      }
      return true;
    },
    [search, filters],
  );

  // Rows surviving the filters, plus their non-matching ancestors as dimmed context.
  const { matchIds, contextIds } = useMemo(() => {
    if (!filtering)
      return {
        matchIds: null as Set<number> | null,
        contextIds: new Set<number>(),
      };
    const matches = new Set(rows.filter((row) => matchesRow(row)).map((r) => r.id));
    const context = new Set<number>();
    for (const id of matches) {
      let parent = byId.get(id)?.parent ?? null;
      while (parent != null && !matches.has(parent)) {
        context.add(parent);
        parent = byId.get(parent)?.parent ?? null;
      }
    }
    return {
      matchIds: matches,
      contextIds: context,
    };
  }, [rows, byId, matchesRow, filtering]);

  // A filtered list opens every branch down to its matches, and rows collapsed in it stay collapsed
  // only while the same filter holds: changing the filter or search starts fully open again. This
  // is kept apart from the tree's own expanded rows, so clearing the filter returns to the tree as
  // it was left.
  const filterSignature = JSON.stringify([
    search.trim(),
    Object.entries(filters).map(([key, values]) => [key, [...values].sort()]),
  ]);
  const [filterFold, setFilterFold] = useState<{ signature: string; collapsed: Set<number> }>({
    signature: "",
    collapsed: new Set(),
  });
  const filterCollapsed =
    filterFold.signature === filterSignature ? filterFold.collapsed : NO_IDS;
  const setFilterCollapsed = (update: (collapsed: Set<number>) => Set<number>) =>
    setFilterFold((prev) => ({
      signature: filterSignature,
      collapsed: update(prev.signature === filterSignature ? prev.collapsed : new Set()),
    }));
  // How many children a row shows in the filtered list, which decides whether it gets a caret.
  const filteredChildCount = useMemo(() => {
    const map = new Map<number, number>();
    if (!matchIds) return map;
    for (const row of rows) {
      if (row.parent == null || !(matchIds.has(row.id) || contextIds.has(row.id))) continue;
      map.set(row.parent, (map.get(row.parent) ?? 0) + 1);
    }
    return map;
  }, [rows, matchIds, contextIds]);

  // Only offer filter values that actually occur, so no filter can promise a value that has no row
  // to land on.
  const filterOptions = useMemo(() => {
    const present = (key: FilterKey) =>
      new Set(rows.flatMap((r) => valuesOf(r, key)).filter((v) => v !== ""));
    const ordered = (order: string[], values: Set<string>) =>
      order.filter((v) => values.has(v));
    return {
      role: ordered(ROLE_ORDER, present("role")),
      kind: ordered(KIND_ORDER, present("kind")),
      bloom: ordered(BLOOM_ORDER, present("bloom")),
      solo: ordered(SOLO_ORDER, present("solo")),
      document: [...present("document")].sort((a, b) => a.localeCompare(b)),
      session: [...present("session")].sort((a, b) => a.localeCompare(b)),
      coverage: ordered(COVERAGE_ORDER, present("coverage")),
    };
  }, [rows]);

  // How many rows each filter value would leave, counted against the search and the other columns'
  // filters but not its own — so the numbers in one popover stay comparable as you tick values.
  const filterCounts = useMemo(() => {
    const counts = {} as Record<FilterKey, Map<string, number>>;
    for (const key of Object.keys(filters) as FilterKey[]) {
      const map = new Map<string, number>();
      for (const row of rows) {
        if (!matchesRow(row, key)) continue;
        for (const value of valuesOf(row, key))
          if (value !== "") map.set(value, (map.get(value) ?? 0) + 1);
      }
      counts[key] = map;
    }
    return counts;
  }, [rows, filters, matchesRow]);

  const toggleFilterValue = (key: FilterKey, value: string) =>
    setFilters((prev) => {
      const next = new Set(prev[key]);
      if (next.has(value)) next.delete(value);
      else next.add(value);
      return { ...prev, [key]: next };
    });

  const clearFilter = (key: FilterKey) =>
    setFilters((prev) => ({ ...prev, [key]: new Set() }));

  const clearAll = () => {
    setSearch("");
    setFilters(noFilters());
  };

  const matchCount = matchIds?.size ?? rows.length;
  // Each chip carries what it alone leaves, counted the way the popover counts a value: against the
  // search and every other chip, but not against its own column. The search chip's own count is the
  // whole result, since nothing else narrows it further.
  const activeChips: FilterChip[] = [];
  if (search.trim())
    activeChips.push({
      label: "Search",
      value: `“${search.trim()}”`,
      count: matchCount,
      onRemove: () => setSearch(""),
    });
  for (const column of COLUMNS) {
    for (const key of column.filterKeys ?? []) {
      for (const value of filters[key]) {
        activeChips.push({
          label: FILTER_LABELS[key],
          value: displayValue(key, value),
          count: filterCounts[key].get(value) ?? 0,
          onRemove: () => toggleFilterValue(key, value),
        });
      }
    }
  }

  return {
    search,
    setSearch,
    filters,
    filtering,
    matchIds,
    contextIds,
    filterCollapsed,
    setFilterCollapsed,
    filteredChildCount,
    filterOptions,
    filterCounts,
    toggleFilterValue,
    clearFilter,
    clearAll,
    matchCount,
    activeChips,
  };
}
