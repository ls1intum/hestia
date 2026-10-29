import type { AttributeKey, ColumnKey, ColumnPrefs, FilterKey } from "./model.ts";

export const COLUMN_PREFS_KEY = "learninggoalhub.competencyTable.columns";

export const MIN_COLUMN_WIDTH = 64;
export const MIN_GOAL_COLUMN_WIDTH = 160;

export const COLUMNS: {
  key: ColumnKey;
  label: string;
  /** Starting width in pixels; the learning-goal column also takes all the free space until resized. */
  width: number;
  /** The attributes the column's funnel filters on; Source offers both its document and session. */
  filterKeys?: FilterKey[];
}[] = [
  { key: "text", label: "Learning goal", width: 240 },
  { key: "role", label: "Tier", width: 80, filterKeys: ["role"] },
  { key: "coverage", label: "Coverage", width: 120, filterKeys: ["coverage"] },
  { key: "kind", label: "Kind", width: 84, filterKeys: ["kind"] },
  { key: "bloom", label: "Bloom", width: 132, filterKeys: ["bloom"] },
  { key: "solo", label: "SOLO", width: 156, filterKeys: ["solo"] },
  {
    key: "source",
    label: "Source",
    width: 168,
    filterKeys: ["document", "session"],
  },
];
export const COLUMN_BY_KEY = new Map(COLUMNS.map((column) => [column.key, column]));
export const ATTRIBUTE_KEYS = COLUMNS.slice(1).map((column) => column.key as AttributeKey);
export const DEFAULT_COLUMN_PREFS: ColumnPrefs = { order: ATTRIBUTE_KEYS, hidden: [], widths: {} };

/** Reads the stored column layout, dropping keys that no longer exist and appending new columns. */
export function loadColumnPrefs(): ColumnPrefs {
  try {
    const stored = JSON.parse(localStorage.getItem(COLUMN_PREFS_KEY) ?? "null") as Partial<ColumnPrefs> | null;
    if (!stored) return DEFAULT_COLUMN_PREFS;
    const known = (keys: unknown) =>
      (Array.isArray(keys) ? keys : []).filter((key): key is AttributeKey =>
        ATTRIBUTE_KEYS.includes(key),
      );
    const order = [...new Set(known(stored.order))];
    const widths: ColumnPrefs["widths"] = {};
    for (const column of COLUMNS) {
      const width = stored.widths?.[column.key];
      if (typeof width === "number" && Number.isFinite(width)) widths[column.key] = width;
    }
    return {
      order: [...order, ...ATTRIBUTE_KEYS.filter((key) => !order.includes(key))],
      hidden: known(stored.hidden),
      widths,
    };
  } catch {
    return DEFAULT_COLUMN_PREFS;
  }
}

/**
 * The grid template shared by the header and every row, so their columns always line up. The
 * learning-goal column fills the free space until it is resized; after that an empty trailing track
 * takes the free space instead, so a narrowed goal column really gets narrower.
 */
export function gridTemplate(keys: ColumnKey[], widths: ColumnPrefs["widths"]): string {
  const goalWidth = widths.text;
  return [
    ...keys.map((key) =>
      key === "text"
        ? goalWidth != null
          ? `${goalWidth}px`
          : `minmax(${COLUMN_BY_KEY.get("text")!.width}px,1fr)`
        : `${widths[key] ?? COLUMN_BY_KEY.get(key)!.width}px`,
    ),
    ...(goalWidth != null ? ["minmax(0,1fr)"] : []),
  ].join(" ");
}
