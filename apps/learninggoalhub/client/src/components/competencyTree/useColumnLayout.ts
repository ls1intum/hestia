import { useEffect, useState } from "react";
import {
  COLUMN_BY_KEY,
  COLUMN_PREFS_KEY,
  DEFAULT_COLUMN_PREFS,
  gridTemplate,
  loadColumnPrefs,
} from "./columns.ts";
import type { AttributeKey, ColumnKey, ColumnPrefs } from "./model.ts";

/**
 * The grid's columns as the reader arranged them: their order, which are hidden and their widths,
 * kept in local storage, plus the header being dragged to a new place.
 */
export function useColumnLayout() {
  const [prefs, setPrefs] = useState<ColumnPrefs>(loadColumnPrefs);
  useEffect(() => {
    try {
      localStorage.setItem(COLUMN_PREFS_KEY, JSON.stringify(prefs));
    } catch {
      // Without storage the layout simply lasts until the page is left.
    }
  }, [prefs]);
  const visibleAttributes = prefs.order.filter((key) => !prefs.hidden.includes(key));
  const visibleColumns = (["text", ...visibleAttributes] as ColumnKey[]).map(
    (key) => COLUMN_BY_KEY.get(key)!,
  );
  const gridCols = gridTemplate(
    visibleColumns.map((column) => column.key),
    prefs.widths,
  );
  const tableMinWidth = visibleColumns.reduce(
    (sum, column) => sum + (prefs.widths[column.key] ?? column.width),
    0,
  );
  // The header being dragged to a new place, and the edge of the header it would land on.
  const [draggedColumn, setDraggedColumn] = useState<AttributeKey | null>(null);
  const [dropTarget, setDropTarget] = useState<{
    key: ColumnKey;
    side: "before" | "after";
  } | null>(null);
  const endColumnDrag = () => {
    setDraggedColumn(null);
    setDropTarget(null);
  };
  const moveColumn = (key: AttributeKey, target: ColumnKey, side: "before" | "after") =>
    setPrefs((prev) => {
      const order = prev.order.filter((other) => other !== key);
      // Nothing moves in front of the learning-goal column.
      const index = target === "text" ? 0 : order.indexOf(target) + (side === "after" ? 1 : 0);
      order.splice(index, 0, key);
      return { ...prev, order };
    });
  const resizeColumn = (key: ColumnKey, width: number | null) =>
    setPrefs((prev) => {
      const widths = { ...prev.widths };
      if (width == null) delete widths[key];
      else widths[key] = width;
      return { ...prev, widths };
    });
  const toggleColumnHidden = (key: AttributeKey) =>
    setPrefs((prev) => ({
      ...prev,
      hidden: prev.hidden.includes(key)
        ? prev.hidden.filter((other) => other !== key)
        : [...prev.hidden, key],
    }));

  return {
    prefs,
    resetPrefs: () => setPrefs(DEFAULT_COLUMN_PREFS),
    visibleAttributes,
    visibleColumns,
    gridCols,
    tableMinWidth,
    draggedColumn,
    setDraggedColumn,
    dropTarget,
    setDropTarget,
    endColumnDrag,
    moveColumn,
    resizeColumn,
    toggleColumnHidden,
  };
}
