import { type MutableRefObject } from "react";
import { useQueryClient, type QueryKey } from "@tanstack/react-query";
import {
  patchExam as apiPatchExam,
  patchTask as apiPatchTask,
  createTask as apiCreateTask,
  deleteTask as apiDeleteTask,
  patchSection as apiPatchSection,
  createSection as apiCreateSection,
  deleteSection as apiDeleteSection,
  patchBlock as apiPatchBlock,
  createBlock as apiCreateBlock,
  deleteBlock as apiDeleteBlock,
  deleteTasksBySection,
  deleteBlocksBySection,
} from "@/lib/api/api-client";
import { examKey, tasksKey } from "@/hooks/data/use-exam";
import { sectionsKey, blocksKey } from "@/hooks/data/use-sections";
import { useSaveStatus } from "@/pages/exam-edit/components/SaveStatus";
import { useSectionConfirmations } from "@/hooks/data/use-section-confirmations";
import type {
  BlockItem,
  Exam,
  Section,
  SectionBlock,
  Task,
  TaskType,
} from "@/lib/exam/exam-helpers";

interface ExamMutationDeps {
  exam: Exam | undefined;
  tasks: Task[] | undefined;
  blocks: SectionBlock[] | undefined;
  sections: Section[] | undefined;
  /** Latest section-confirmation API (read via a ref so edit closures stay stable). */
  confirmApiRef: MutableRefObject<ReturnType<typeof useSectionConfirmations>>;
  /** Flag an Add action so the newly created block auto-expands (see ExamEdit). */
  markPendingAdd: () => void;
}

/**
 * The editor's CRUD/mutation layer, extracted from ExamEdit. Every mutator
 * shares the same envelope — optionally `unconfirmIfNeeded` → `setSaving()` →
 * optimistic cache write → `try { await api; setSaved() } catch { setError() }`.
 * A handful of paths deliberately deviate (add-paths invalidate instead of
 * optimistically writing; `patchExam` skips unconfirm; `patchSection` skips it
 * when toggling `confirmed_at`); those keep their bespoke bodies.
 */
export function useExamMutations(
  id: string | undefined,
  { exam, tasks, blocks, sections, confirmApiRef, markPendingAdd }: ExamMutationDeps,
) {
  const qc = useQueryClient();
  const { setSaving, setSaved, setError } = useSaveStatus();

  const sectionIdForTask = (taskId: string): string | null => {
    const t = (tasks ?? []).find((tk) => tk.id === taskId);
    return t?.section_id ?? null;
  };

  const sectionIdForBlock = (blockId: string): string | null => {
    const b = (blocks ?? []).find((bk) => bk.id === blockId);
    return b?.section_id ?? null;
  };

  // Unconfirm the given section (if confirmed) before performing an edit.
  // Centralised so every mutation path keeps the confirmation/answer state
  // honest: confirmation is a commit point, any edit reopens it.
  const unconfirmIfNeeded = async (sectionId: string | null | undefined) => {
    if (sectionId && confirmApiRef.current.isConfirmed(sectionId)) {
      await confirmApiRef.current.unconfirm(sectionId);
    }
  };

  // The plain envelope: flip the save indicator, run the work, settle status.
  const withSaveStatus = async (fn: () => Promise<void>) => {
    setSaving();
    try {
      await fn();
      setSaved();
    } catch {
      setError();
    }
  };

  // Optimistically map over a cached list query.
  const optimisticListUpdate = <T,>(
    key: QueryKey,
    updater: (prev: T[]) => T[],
  ) => {
    qc.setQueryData<T[]>(key, (prev) => updater(prev ?? []));
  };

  const patchExam = async (patch: Partial<Exam>) => {
    if (!id || !exam) return;
    await withSaveStatus(async () => {
      qc.setQueryData(examKey(id), { ...exam, ...patch });
      await apiPatchExam(id, patch as Record<string, unknown>);
    });
  };

  const patchTask = async (taskId: string, patch: Partial<Task>) => {
    if (!id) return;
    await unconfirmIfNeeded(sectionIdForTask(taskId));
    await withSaveStatus(async () => {
      optimisticListUpdate<Task>(tasksKey(id), (prev) =>
        prev.map((t) => (t.id === taskId ? { ...t, ...patch } : t)),
      );
      await apiPatchTask(taskId, patch as Record<string, unknown>);
    });
  };

  const addTask = async (
    type: TaskType,
    afterPosition: number,
    sectionId: string | null,
  ) => {
    if (!id) return;
    await unconfirmIfNeeded(sectionId);
    setSaving();
    markPendingAdd();
    try {
      await apiCreateTask({
        exam_id: id,
        position: afterPosition + 1,
        type,
        section_id: sectionId,
        options:
          type === "text"
            ? null
            : [
                { id: crypto.randomUUID(), text: "", is_correct: false },
                { id: crypto.randomUUID(), text: "", is_correct: false },
              ],
      });
      setSaved();
      qc.invalidateQueries({ queryKey: tasksKey(id) });
    } catch {
      setError();
    }
  };

  const deleteTask = async (taskId: string) => {
    if (!id) return;
    await unconfirmIfNeeded(sectionIdForTask(taskId));
    await withSaveStatus(async () => {
      optimisticListUpdate<Task>(tasksKey(id), (prev) =>
        prev.filter((t) => t.id !== taskId),
      );
      await apiDeleteTask(taskId);
    });
  };

  const duplicateTask = async (task: Task) => {
    if (!id) return;
    await unconfirmIfNeeded(task.section_id);
    setSaving();
    try {
      await apiCreateTask({
        exam_id: id,
        position: task.position + 1,
        type: task.type,
        prompt: task.prompt,
        options: task.options ?? null,
        reference_answer: task.reference_answer,
        section: task.section,
        points: task.points,
        section_id: task.section_id,
      });
      setSaved();
      qc.invalidateQueries({ queryKey: tasksKey(id) });
    } catch {
      setError();
    }
  };

  const patchSection = async (sectionId: string, patch: Partial<Section>) => {
    if (!id) return;
    // Confirmation toggles flow through useSectionConfirmations and bypass
    // this helper, so any patchSection call here is a user-initiated edit
    // and should reopen the section.
    if (!("confirmed_at" in patch)) {
      await unconfirmIfNeeded(sectionId);
    }
    await withSaveStatus(async () => {
      optimisticListUpdate<Section>(sectionsKey(id), (prev) =>
        prev.map((s) => (s.id === sectionId ? { ...s, ...patch } : s)),
      );
      await apiPatchSection(sectionId, patch as Record<string, unknown>);
    });
  };

  const patchBlock = async (blockId: string, patch: Partial<SectionBlock>) => {
    if (!id) return;
    await unconfirmIfNeeded(sectionIdForBlock(blockId));
    await withSaveStatus(async () => {
      optimisticListUpdate<SectionBlock>(blocksKey(id), (prev) =>
        prev.map((b) => (b.id === blockId ? { ...b, ...patch } : b)),
      );
      await apiPatchBlock(blockId, patch as Record<string, unknown>);
    });
  };

  const addContextBlock = async (afterPosition: number, sectionId: string) => {
    if (!id) return;
    await unconfirmIfNeeded(sectionId);
    setSaving();
    markPendingAdd();
    try {
      await apiCreateBlock({
        exam_id: id,
        section_id: sectionId,
        position: afterPosition + 1,
        content: "",
      });
      setSaved();
      qc.invalidateQueries({ queryKey: blocksKey(id) });
      qc.invalidateQueries({ queryKey: tasksKey(id) });
    } catch (error) {
      console.error("addContextBlock", error);
      setError();
    }
  };

  const addFigureBlock = async (afterPosition: number, sectionId: string) => {
    if (!id) return;
    await unconfirmIfNeeded(sectionId);
    setSaving();
    markPendingAdd();
    try {
      await apiCreateBlock({
        exam_id: id,
        section_id: sectionId,
        position: afterPosition + 1,
        content: "",
        kind: "figure",
      });
      setSaved();
      qc.invalidateQueries({ queryKey: blocksKey(id) });
      qc.invalidateQueries({ queryKey: tasksKey(id) });
    } catch {
      setError();
    }
  };

  const deleteBlock = async (blockId: string) => {
    if (!id) return;
    await unconfirmIfNeeded(sectionIdForBlock(blockId));
    await withSaveStatus(async () => {
      optimisticListUpdate<SectionBlock>(blocksKey(id), (prev) =>
        prev.filter((b) => b.id !== blockId),
      );
      await apiDeleteBlock(blockId);
    });
  };

  const addSection = async (afterPosition?: number) => {
    if (!id) return;
    setSaving();
    // The backend's create-section is transactional and shifts later sections
    // down itself, so we just hand it the target position (append falls past
    // the end, where nothing needs shifting).
    const position =
      afterPosition != null ? afterPosition + 1 : (sections?.length ?? 0) + 1;
    try {
      await apiCreateSection({ exam_id: id, name: "", position });
      setSaved();
      qc.invalidateQueries({ queryKey: sectionsKey(id) });
    } catch {
      setError();
    }
  };

  const deleteSection = async (sectionId: string) => {
    if (!id) return;
    setSaving();
    // Delete tasks, blocks, then the section itself
    try {
      await deleteTasksBySection(id, sectionId);
      await deleteBlocksBySection(id, sectionId);
      await apiDeleteSection(sectionId);
      setSaved();
      qc.invalidateQueries({ queryKey: sectionsKey(id) });
      qc.invalidateQueries({ queryKey: tasksKey(id) });
      qc.invalidateQueries({ queryKey: blocksKey(id) });
    } catch {
      setError();
    }
  };

  /**
   * Persist a new ordering of items within a section. Assigns sequential
   * positions (0, 1, 2, ...) and writes only the rows whose position changed.
   * Cache is updated optimistically so the UI does not flicker.
   */
  const persistReorder = async (newOrder: BlockItem[]) => {
    if (!id) return;
    // Items in a single reorder all belong to the same section (the call
    // originates from one section's DndContext). Take the section id from
    // the first item that exposes one and reopen its confirmation.
    const firstWithSection = newOrder.find(
      (it) =>
        (it.kind === "task" && it.task.section_id) ||
        it.kind === "context" ||
        it.kind === "figure",
    );
    const reorderSectionId =
      firstWithSection?.kind === "task"
        ? firstWithSection.task.section_id
        : firstWithSection?.kind === "context" || firstWithSection?.kind === "figure"
          ? firstWithSection.block.section_id
          : null;
    await unconfirmIfNeeded(reorderSectionId);
    setSaving();

    const taskUpdates: Array<{ id: string; position: number }> = [];
    const blockUpdates: Array<{ id: string; position: number }> = [];

    newOrder.forEach((item, idx) => {
      if (item.kind === "task" && item.task.position !== idx) {
        taskUpdates.push({ id: item.task.id, position: idx });
      }
      if (
        (item.kind === "context" || item.kind === "figure") &&
        item.block.position !== idx
      ) {
        blockUpdates.push({ id: item.block.id, position: idx });
      }
    });

    // Optimistic cache update
    if (taskUpdates.length > 0) {
      const map = new Map(taskUpdates.map((u) => [u.id, u.position]));
      qc.setQueryData<Task[]>(tasksKey(id), (prev) =>
        (prev ?? []).map((t) =>
          map.has(t.id) ? { ...t, position: map.get(t.id)! } : t,
        ),
      );
    }
    if (blockUpdates.length > 0) {
      const map = new Map(blockUpdates.map((u) => [u.id, u.position]));
      qc.setQueryData<SectionBlock[]>(blocksKey(id), (prev) =>
        (prev ?? []).map((b) =>
          map.has(b.id) ? { ...b, position: map.get(b.id)! } : b,
        ),
      );
    }

    try {
      await Promise.all([
        ...taskUpdates.map((u) => apiPatchTask(u.id, { position: u.position })),
        ...blockUpdates.map((u) => apiPatchBlock(u.id, { position: u.position })),
      ]);
      setSaved();
    } catch {
      setError();
      qc.invalidateQueries({ queryKey: tasksKey(id) });
      qc.invalidateQueries({ queryKey: blocksKey(id) });
    }
  };

  return {
    patchExam,
    patchTask,
    addTask,
    deleteTask,
    duplicateTask,
    patchSection,
    patchBlock,
    addContextBlock,
    addFigureBlock,
    deleteBlock,
    addSection,
    deleteSection,
    persistReorder,
  };
}
