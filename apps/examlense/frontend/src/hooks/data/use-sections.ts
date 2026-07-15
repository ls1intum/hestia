import { useQuery, useQueryClient } from "@tanstack/react-query";
import { listSections, listBlocks, listFigures } from "@/lib/api/api-client";
import type { Section, SectionBlock, SectionFigure } from "@/lib/exam/exam-helpers";

export const sectionsKey = (examId: string) => ["sections", examId] as const;
export const blocksKey = (examId: string) => ["section-blocks", examId] as const;
export const figuresKey = (blockId: string) => ["section-figures", blockId] as const;

export function useSections(examId: string | undefined) {
  return useQuery({
    queryKey: examId ? sectionsKey(examId) : ["sections", "missing"],
    enabled: !!examId,
    queryFn: async () => (await listSections(examId!)) as unknown as Section[],
  });
}

export function useSectionBlocks(examId: string | undefined) {
  return useQuery({
    queryKey: examId ? blocksKey(examId) : ["section-blocks", "missing"],
    enabled: !!examId,
    queryFn: async () => (await listBlocks(examId!)) as unknown as SectionBlock[],
  });
}

export function useSectionFigures(blockId: string | undefined) {
  return useQuery({
    queryKey: blockId ? figuresKey(blockId) : ["section-figures", "missing"],
    enabled: !!blockId,
    queryFn: async () => (await listFigures(blockId!)) as unknown as SectionFigure[],
  });
}

export function useInvalidateSections() {
  const qc = useQueryClient();
  return (examId: string) => qc.invalidateQueries({ queryKey: sectionsKey(examId) });
}
