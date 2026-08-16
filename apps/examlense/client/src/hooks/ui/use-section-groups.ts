import { useEffect, useMemo, useState } from "react";
import {
  figureLabelsForBlocks,
  letterLabel,
  mergeSectionItems,
  sectionIndexSlug,
  UNASSIGNED_SLUG,
  type BlockItem,
  type Section,
  type SectionBlock,
  type Task,
} from "@/lib/exam/exam-helpers";

export interface SectionGroup {
  section: Section | null;
  tasks: Task[];
  items: BlockItem[];
  slug: string;
}

interface SectionGroupsOptions {
  /**
   * When true (editor), every section is kept even if empty and the orphan
   * bucket is appended only when it holds tasks. When false (grading), groups
   * with no items are dropped entirely.
   */
  includeEmpty: boolean;
}

/**
 * Group tasks + blocks by section. Pure (no hooks) so it can be unit-tested;
 * `useSectionGroups` memoizes it. Sections keep their sorted order and get an
 * index-based slug; unassigned tasks fall into a trailing "orphan" group. When
 * `includeEmpty` is false, groups with no items are dropped.
 */
export function computeSectionGroups(
  sections: Section[] | undefined,
  tasks: Task[] | undefined,
  blocks: SectionBlock[] | undefined,
  includeEmpty: boolean,
): SectionGroup[] {
  const sortedSections = (sections ?? [])
    .slice()
    .sort((a, b) => a.position - b.position);
  const tasksBySection = new Map<string | null, Task[]>();
  for (const task of tasks ?? []) {
    const key = task.section_id ?? null;
    const arr = tasksBySection.get(key) ?? [];
    arr.push(task);
    tasksBySection.set(key, arr);
  }
  const blocksBySection = new Map<string, SectionBlock[]>();
  for (const block of blocks ?? []) {
    const arr = blocksBySection.get(block.section_id) ?? [];
    arr.push(block);
    blocksBySection.set(block.section_id, arr);
  }

  const groups: SectionGroup[] = [];
  sortedSections.forEach((s, index) => {
    const sectionTasks = tasksBySection.get(s.id) ?? [];
    const sectionBlocks = blocksBySection.get(s.id) ?? [];
    groups.push({
      section: s,
      tasks: sectionTasks,
      items: mergeSectionItems(sectionTasks, sectionBlocks),
      slug: sectionIndexSlug(index),
    });
  });

  const orphan = tasksBySection.get(null) ?? [];
  if (orphan.length > 0) {
    groups.push({
      section: null,
      tasks: orphan,
      items: mergeSectionItems(orphan, []),
      slug: UNASSIGNED_SLUG,
    });
  }

  return includeEmpty ? groups : groups.filter((g) => g.items.length > 0);
}

/** Per-task letter labels (a, b, c…) within each section, ordered by position. */
export function computeTaskLetters(grouped: SectionGroup[]): Map<string, string> {
  const m = new Map<string, string>();
  grouped.forEach((g) => {
    g.tasks
      .slice()
      .sort((a, b) => a.position - b.position)
      .forEach((task, i) => m.set(task.id, letterLabel(i)));
  });
  return m;
}

/**
 * Group an exam's tasks + blocks by section for the editor and grading views.
 * Returns the grouping plus the per-task letter labels and the figure display
 * labels that both views derive from it.
 */
export function useSectionGroups(
  sections: Section[] | undefined,
  tasks: Task[] | undefined,
  blocks: SectionBlock[] | undefined,
  { includeEmpty }: SectionGroupsOptions,
): {
  grouped: SectionGroup[];
  taskLetterById: Map<string, string>;
  figureLabels: Map<string, string>;
} {
  const grouped = useMemo(
    () => computeSectionGroups(sections, tasks, blocks, includeEmpty),
    [tasks, sections, blocks, includeEmpty],
  );

  const taskLetterById = useMemo(() => computeTaskLetters(grouped), [grouped]);

  const figureLabels = useMemo(
    () => figureLabelsForBlocks(sections, blocks),
    [sections, blocks],
  );

  return { grouped, taskLetterById, figureLabels };
}

interface CurrentSectionIdOptions {
  /**
   * When true, an out-of-range current id resets to "" (empty) instead of the
   * first group — the editor uses this to keep the intro slide visible until
   * the user picks a section.
   */
  introGate?: boolean;
}

/**
 * Track the carousel's current section slug, bootstrapped from the URL hash
 * and re-validated whenever the grouping changes (a deleted section falls back
 * to the first group, or to "" when the editor's intro is still pending).
 */
export function useCurrentSectionId(
  grouped: SectionGroup[],
  { introGate = false }: CurrentSectionIdOptions = {},
): [string, (id: string) => void] {
  const [currentId, setCurrentId] = useState<string>(() => {
    if (typeof window === "undefined") return "";
    return window.location.hash.replace(/^#/, "");
  });

  useEffect(() => {
    // Keep the current id (e.g. a hash deep-link) untouched while the grouping
    // is still empty during initial load.
    if (grouped.length === 0) return;
    const validIds = new Set(grouped.map((g) => g.slug));
    setCurrentId((prev) => {
      if (validIds.has(prev)) return prev;
      if (introGate) return "";
      return grouped[0]?.slug ?? "";
    });
  }, [grouped, introGate]);

  return [currentId, setCurrentId];
}
