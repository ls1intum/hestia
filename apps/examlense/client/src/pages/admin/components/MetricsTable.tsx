import type { ReactNode } from "react";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils/utils";

export interface MetricsColumn<T> {
  header: string;
  align?: "left" | "right";
  cell: (row: T) => ReactNode;
}

/**
 * "By model" style breakdown table shared by the admin review panels: a titled
 * card wrapping a bordered table driven by column defs, so each panel supplies
 * its own columns/cells while the scaffold (borders, spacing, tabular numbers)
 * stays consistent.
 */
export function MetricsTable<T>({
  title,
  columns,
  rows,
  getRowKey,
}: {
  title: string;
  columns: MetricsColumn<T>[];
  rows: T[];
  getRowKey: (row: T) => string;
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="font-body text-lg">{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-hestia-border text-left text-xs text-hestia-text-muted">
              {columns.map((col) => (
                <th
                  key={col.header}
                  className={cn(
                    "pb-hestia-2 font-medium",
                    col.align === "right" && "text-right",
                  )}
                >
                  {col.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr
                key={getRowKey(row)}
                className="border-b border-hestia-border/40 last:border-0"
              >
                {columns.map((col) => (
                  <td
                    key={col.header}
                    className={cn(
                      "py-hestia-2 text-hestia-text",
                      col.align === "right" && "text-right tabular-nums",
                    )}
                  >
                    {col.cell(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </CardContent>
    </Card>
  );
}
