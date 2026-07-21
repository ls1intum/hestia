import { useMemo } from "react";
import { useQueries } from "@tanstack/react-query";
import { listFigures } from "@/lib/api/api-client";
import { figuresKey } from "@/hooks/data/use-sections";
import {
  isBlockItemEmpty,
  type BlockItem,
  type SectionFigure,
} from "@/lib/exam/exam-helpers";

/**
 * Detects blocks in a section that are missing content — a task with no prompt,
 * a blank context block, or a figure block with no uploaded image. Figures are
 * only stored per-block, so we batch-fetch them here via useQueries (sharing the
 * figuresKey cache with FigureBlockCard). A figure block counts as empty only
 * once its query has resolved to []; while a query is still pending we treat the
 * block as complete to avoid a false "Content missing" flash.
 */
export function useSectionMissingContent(items: BlockItem[] | undefined) {
  const figureBlockIds = useMemo(
    () =>
      (items ?? [])
        .filter((it) => it.kind === "figure")
        .map((it) => it.block.id),
    [items],
  );

  const figureQueries = useQueries({
    queries: figureBlockIds.map((bid) => ({
      queryKey: figuresKey(bid),
      queryFn: async () =>
        (await listFigures(bid)) as unknown as SectionFigure[],
      enabled: !!bid,
    })),
  });

  const emptyFigureBlockIds = useMemo(() => {
    const empty = new Set<string>();
    figureBlockIds.forEach((bid, i) => {
      const q = figureQueries[i];
      if (q?.isSuccess && (q.data?.length ?? 0) === 0) empty.add(bid);
    });
    return empty;
  }, [figureBlockIds, figureQueries]);

  const missingItems = useMemo(
    () => (items ?? []).filter((it) => isBlockItemEmpty(it, emptyFigureBlockIds)),
    [items, emptyFigureBlockIds],
  );

  return { missingItems, hasMissing: missingItems.length > 0 };
}
