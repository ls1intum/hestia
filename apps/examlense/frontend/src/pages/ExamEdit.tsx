import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Link, useParams, Navigate, useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import {
  FileText,
  ArrowLeft,
  ListChecks,
  ListTodo,
  NotebookPen,
  Image as ImageIcon,
  Plus,
} from "lucide-react";
import {
  DndContext,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
  closestCenter,
  type DragEndEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from "@dnd-kit/sortable";
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
  listAnswers,
  cancelExam,
} from "@/lib/api-client";
import { subscribeExam } from "@/lib/sse";
import { useExam, useTasks, examKey, tasksKey } from "@/hooks/use-exam";
import { useSections, useSectionBlocks, sectionsKey, blocksKey } from "@/hooks/use-sections";
import { SaveStatusProvider, useSaveStatus, SaveIndicator } from "@/components/SaveStatus";
import { useToast } from "@/hooks/use-toast";
import { examLearningGoalsKey } from "@/hooks/use-learning-goals";
import { TaskCard } from "@/components/exam-edit/TaskCard";
import { ContextBlockCard } from "@/components/exam-edit/ContextBlockCard";
import { FigureBlockCard } from "@/components/exam-edit/FigureBlockCard";
import { AddTaskInline } from "@/components/exam-edit/AddTaskInline";
import { SectionLayout } from "@/components/exam-edit/SectionLayout";
import { SectionTitleInput } from "@/components/exam-edit/SectionTitleInput";
import { useItemCollapseState } from "@/hooks/use-item-collapse-state";
import { BlockRow } from "@/components/exam-edit/BlockRow";
import { SortableItem } from "@/components/exam-edit/SortableItem";
import { ExamEditFooter } from "@/components/exam-edit/ExamEditFooter";
import { ScoreNeededIndicator } from "@/components/exam-edit/ScoreNeededIndicator";
import { InlineTitle } from "@/components/exam-edit/chrome/InlineTitle";
import { DEFAULT_SOLVER_MODEL_ID } from "@/lib/llm-models";
import { solveExam } from "@/lib/api-solve";
import { EvaluatingView } from "@/components/exam-edit/EvaluatingView";
import { EditorLoadingView } from "@/components/exam-edit/EditorLoadingView";
import { SectionCarousel, type CarouselSlide } from "@/components/exam-edit/SectionCarousel";
import { IntroSlide } from "@/components/exam-edit/IntroSlide";
import { ManualIntroSlide } from "@/components/exam-edit/ManualIntroSlide";
import { useSectionConfirmations } from "@/hooks/use-section-confirmations";
import {
  convertTaskType,
  figureLabelsForBlocks,
  isSectionReady,
  itemId,
  letterLabel,
  mergeSectionItems,
  totalPoints,
  type BlockItem,
  type Exam,
  type Section,
  type SectionBlock,
  type Task,
  type TaskType,
} from "@/lib/exam-helpers";
import { TASK_TYPE_LABELS } from "@/lib/labels";
import { Badge } from "@/components/ui/badge";
import {
  SectionSidebar,
  useEditSectionEntries,
  type SectionEntry,
} from "@/components/exam-edit/SectionSidebar";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";

const taskTypeIcon = (type: TaskType) => {
  switch (type) {
    case "single_choice":
      return ListTodo;
    case "multiple_choice":
      return ListChecks;
    case "text":
    default:
      return NotebookPen;
  }
};

const sectionIndexSlug = (index: number) => `section-${index + 1}`;

const ExamEditInner = () => {
  const { id } = useParams<{ id: string }>();
  const qc = useQueryClient();
  const { toast } = useToast();
  const { setSaving, setSaved, setError } = useSaveStatus();
  const { data: exam, isLoading: examLoading } = useExam(id);
  const { data: tasks, isLoading: tasksLoading } = useTasks(id);
  const { data: sections, isLoading: sectionsLoading } = useSections(id);
  const { data: blocks, isLoading: blocksLoading } = useSectionBlocks(id);
  const isLoading =
    examLoading || tasksLoading || sectionsLoading || blocksLoading;

  const solverModelId =
    exam?.solver_model ?? DEFAULT_SOLVER_MODEL_ID;

  // Keep the exam in sync if its status changes server-side (e.g. background
  // evaluation flips it back to a non-evaluating state).
  useEffect(() => {
    if (!id) return;
    // The SSE `exam` event carries no payload, so we can't diff status here.
    // Invalidate the exam and its child collections together: when the
    // background pipeline transitions out of a long-running phase
    // (parsing/evaluating/grading), the child collections were empty at first
    // load, and refetching them renders the freshly created
    // sections/tasks/blocks without a manual refresh.
    return subscribeExam(id, {
      onExam: () => {
        qc.invalidateQueries({ queryKey: examKey(id) });
        qc.invalidateQueries({ queryKey: tasksKey(id) });
        qc.invalidateQueries({ queryKey: sectionsKey(id) });
        qc.invalidateQueries({ queryKey: blocksKey(id) });
      },
      // Learning goals were generated in the background — refresh the tasks
      // (they carry the goal ids) and the resolved-goal cache.
      onTasks: () => {
        qc.invalidateQueries({ queryKey: tasksKey(id) });
        qc.invalidateQueries({ queryKey: examLearningGoalsKey(id) });
      },
    });
  }, [id, qc]);

  // Per-section user-confirmation state. Confirming a section dispatches a
  // background solve-section call so the LLM answers are ready by the time
  // the user clicks Ready. The ref lets edit-path closures read the latest
  // confirm state without being recreated.
  const confirmApi = useSectionConfirmations(id, sections, tasks);
  const confirmApiRef = useRef(confirmApi);
  confirmApiRef.current = confirmApi;

  const navigate = useNavigate();

  // Cancel an in-progress parse/evaluate: the backend reverts the exam to
  // `failed` and, crucially, stops the fire-and-forget job from resurrecting it
  // when its LLM call eventually returns. Then leave for the exam list.
  const handleCancelProcessing = async () => {
    if (!id) return;
    try {
      await cancelExam(id);
    } catch (err) {
      // 409 = it already finished between render and click; nothing to cancel.
      console.error("cancel processing failed", err);
      toast({ title: "Could not cancel — it may have already finished.", variant: "destructive" });
    }
    qc.invalidateQueries({ queryKey: examKey(id) });
    qc.invalidateQueries({ queryKey: ["exams-list"] });
    navigate("/exams");
  };

  // True once the user sends this exam to solving in this session. Lets us
  // show the manual "Continue to grading" flow only for a just-finished solve,
  // while a directly-opened already-graded exam still auto-redirects.
  const evaluationStartedRef = useRef(false);

  // The scrolling content viewport — used by the "Score needs to be set"
  // indicator to place itself relative to the visible area.
  const scrollRef = useRef<HTMLDivElement>(null);

  const sectionIdForTask = useCallback(
    (taskId: string): string | null => {
      const t = (tasks ?? []).find((tk) => tk.id === taskId);
      return t?.section_id ?? null;
    },
    [tasks],
  );

  const sectionIdForBlock = useCallback(
    (blockId: string): string | null => {
      const b = (blocks ?? []).find((bk) => bk.id === blockId);
      return b?.section_id ?? null;
    },
    [blocks],
  );

  // Unconfirm the given section (if confirmed) before performing an edit.
  // Centralised so every mutation path keeps the confirmation/answer state
  // honest: confirmation is a commit point, any edit reopens it.
  const unconfirmIfNeeded = useCallback(
    async (sectionId: string | null | undefined) => {
      if (sectionId && confirmApiRef.current.isConfirmed(sectionId)) {
        await confirmApiRef.current.unconfirm(sectionId);
      }
    },
    [],
  );

  const patchExam = async (patch: Partial<Exam>) => {
    if (!id || !exam) return;
    setSaving();
    qc.setQueryData(examKey(id), { ...exam, ...patch });
    try {
      await apiPatchExam(id, patch as Record<string, unknown>);
      setSaved();
    } catch {
      setError();
    }
  };

  const sendToEvaluation = async () => {
    if (!id || !exam) return;
    evaluationStartedRef.current = true;
    setSaving();

    // Step 1: flip status to "evaluating" up front so EvaluatingView
    // renders immediately. All the waiting (in-flight pre-solves, answer
    // check, optional solve-exam fallback) happens with the loading
    // screen on screen, instead of the user staring at the editor.
    qc.setQueryData(examKey(id), { ...exam, status: "evaluating" });
    try {
      await apiPatchExam(id, { status: "evaluating" });
    } catch {
      qc.setQueryData(examKey(id), exam);
      setError();
      return;
    }
    qc.invalidateQueries({ queryKey: examKey(id) });
    qc.invalidateQueries({ queryKey: ["exams-list"] });

    // Step 2: wait for any background pre-solves we kicked off on confirm
    // to settle. Without this, a rushed Ready click would race with an
    // in-flight solve-section and trigger a duplicate dispatch from
    // solve-exam.
    const inFlight = confirmApiRef.current.getInFlight();
    if (inFlight.length > 0) {
      await Promise.allSettled(inFlight);
    }

    // Step 3: check the persisted answers against the live task list to
    // decide whether evaluation is needed at all.
    let answerRows: { task_id: string }[] = [];
    try {
      answerRows = await listAnswers(id);
    } catch (err) {
      console.error("load answers failed", err);
    }
    const answered = new Set(answerRows.map((r) => r.task_id));
    const allTasks = tasks ?? [];
    const everyTaskAnswered =
      allTasks.length > 0 && allTasks.every((t) => answered.has(t.id));

    // Step 4a: every task has a pre-solved answer. Skip the evaluation
    // phase entirely and hand the user straight to grading.
    if (everyTaskAnswered) {
      try {
        await apiPatchExam(id, { status: "grading" });
        setSaved();
        qc.invalidateQueries({ queryKey: examKey(id) });
        qc.invalidateQueries({ queryKey: ["exams-list"] });
        return;
      } catch {
        setError();
        // Fall through to solve-exam as a recovery so the exam does not
        // remain stuck in evaluating.
      }
    }

    // Step 4b: at least one section never produced answers (e.g. a
    // pre-solve failed, or the orphan bucket was never solved). Hand off
    // to the existing solve-exam orchestrator; the per-section
    // solve_lock ensures it will not restart sections that are still
    // mid-flight. solve-exam flips status to grading when done.
    setSaved();
    solveExam(id).catch((err) => {
      console.error("solve-exam invocation failed", err);
    });
  };

  const patchTask = async (taskId: string, patch: Partial<Task>) => {
    if (!id) return;
    await unconfirmIfNeeded(sectionIdForTask(taskId));
    setSaving();
    qc.setQueryData<Task[]>(tasksKey(id), (prev) =>
      (prev ?? []).map((t) => (t.id === taskId ? { ...t, ...patch } : t)),
    );
    try {
      await apiPatchTask(taskId, patch as Record<string, unknown>);
      setSaved();
    } catch {
      setError();
    }
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
    setSaving();
    qc.setQueryData<Task[]>(tasksKey(id), (prev) =>
      (prev ?? []).filter((t) => t.id !== taskId),
    );
    try {
      await apiDeleteTask(taskId);
      setSaved();
    } catch {
      setError();
    }
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
    setSaving();
    qc.setQueryData<Section[]>(sectionsKey(id), (prev) =>
      (prev ?? []).map((s) => (s.id === sectionId ? { ...s, ...patch } : s)),
    );
    try {
      await apiPatchSection(sectionId, patch as Record<string, unknown>);
      setSaved();
    } catch {
      setError();
    }
  };

  const patchBlock = async (
    blockId: string,
    patch: Partial<SectionBlock>,
  ) => {
    if (!id) return;
    await unconfirmIfNeeded(sectionIdForBlock(blockId));
    setSaving();
    qc.setQueryData<SectionBlock[]>(blocksKey(id), (prev) =>
      (prev ?? []).map((b) => (b.id === blockId ? { ...b, ...patch } : b)),
    );
    try {
      await apiPatchBlock(blockId, patch as Record<string, unknown>);
      setSaved();
    } catch {
      setError();
    }
  };

  const addContextBlock = async (
    afterPosition: number,
    sectionId: string,
  ) => {
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

  const addFigureBlock = async (
    afterPosition: number,
    sectionId: string,
  ) => {
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
    setSaving();
    qc.setQueryData<SectionBlock[]>(blocksKey(id), (prev) =>
      (prev ?? []).filter((b) => b.id !== blockId),
    );
    try {
      await apiDeleteBlock(blockId);
      setSaved();
    } catch {
      setError();
    }
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

  // Group tasks + context blocks by section_id (preserving section order; unassigned tasks go last)
  const grouped = useMemo(() => {
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

    const groups: Array<{
      section: Section | null;
      tasks: Task[];
      items: BlockItem[];
      slug: string;
    }> = [];
    for (const [index, s] of sortedSections.entries()) {
      const sectionTasks = tasksBySection.get(s.id) ?? [];
      const sectionBlocks = blocksBySection.get(s.id) ?? [];
      groups.push({
        section: s,
        tasks: sectionTasks,
        items: mergeSectionItems(sectionTasks, sectionBlocks),
        slug: sectionIndexSlug(index),
      });
    }
    const orphan = tasksBySection.get(null) ?? [];
    if (orphan.length > 0) {
      orphan.sort((a, b) => a.position - b.position);
      groups.push({
        section: null,
        tasks: orphan,
        items: mergeSectionItems(orphan, []),
        slug: "section-unassigned",
      });
    }
    return groups;
  }, [tasks, sections, blocks]);

  // Auto-generated labels for figure blocks: "Figure {section}.{index}".
  const figureLabels = useMemo(
    () => figureLabelsForBlocks(sections, blocks),
    [sections, blocks],
  );

  // Last position used inside a given section (across tasks + blocks).
  const lastPositionIn = (group: { tasks: Task[]; items: BlockItem[] }) => {
    let max = 0;
    for (const it of group.items) if (it.position > max) max = it.position;
    return max;
  };

  // Per-item collapse state (covers tasks + context blocks)
  const collapseApi = useItemCollapseState(id);

  // Drop confirmations whenever a section's readiness flips back to false
  // (e.g. user removed a task's score). Edit-path wrappers also unconfirm
  // proactively; this catches readiness-only changes that the wrappers
  // could miss (e.g. an external/realtime update).
  //
  // Gate on both queries having actually returned: on first load `sections`
  // can settle before `tasks`, which would briefly make every section look
  // empty and unconfirm the entire exam.
  useEffect(() => {
    if (tasksLoading || sectionsLoading) return;
    if (!tasks || !sections) return;
    for (const g of grouped) {
      const sid = g.section?.id;
      if (!sid) continue;
      if (!confirmApiRef.current.isConfirmed(sid)) continue;
      if (!isSectionReady(g.tasks)) {
        void confirmApiRef.current.unconfirm(sid);
      }
    }
  }, [grouped, tasks, sections, tasksLoading, sectionsLoading]);

  const [pendingDeleteSection, setPendingDeleteSection] = useState<{
    id: string;
    label: string;
  } | null>(null);

  // Open state for the footer's "Send for evaluation" dialog. Lifted here so
  // confirming the final section can open it directly (see handleAdvanceSection).
  const [startSolvingOpen, setStartSolvingOpen] = useState(false);

  const showInlineIntro = exam?.source === "pdf" || exam?.source === "manual";
  const introKey = id ? `examlense-intro-done:${id}` : "";
  const [introComplete, setIntroComplete] = useState(true);

  useEffect(() => {
    if (!showInlineIntro || !introKey) {
      setIntroComplete(true);
      return;
    }
    setIntroComplete(localStorage.getItem(introKey) === "1");
  }, [introKey, showInlineIntro]);

  const [currentSectionId, setCurrentSectionId] = useState<string>(() => {
    if (typeof window !== "undefined") {
      const hashed = window.location.hash.replace(/^#/, "");
      if (hashed) return hashed;
    }
    return "";
  });

  useEffect(() => {
    if (grouped.length === 0) return;
    const validIds = new Set(grouped.map((g) => g.slug));
    setCurrentSectionId((prev) => {
      if (validIds.has(prev)) return prev;
      if (showInlineIntro && !introComplete) return "";
      return grouped[0]?.slug ?? "";
    });
  }, [grouped, introComplete, showInlineIntro]);

  const markIntroComplete = useCallback(() => {
    if (introKey) localStorage.setItem(introKey, "1");
    setIntroComplete(true);
  }, [introKey]);

  const handleSelectSection = useCallback(
    (slug: string) => {
      if (showInlineIntro && !introComplete) markIntroComplete();
      setCurrentSectionId(slug);
    },
    [introComplete, markIntroComplete, showInlineIntro],
  );

  // Auto-expand newly created blocks. We only treat ids as "new" right
  // after the user invokes an Add action (addTask / addContextBlock /
  // addFigureBlock). Without a pending counter, async query waves and
  // realtime invalidations would falsely classify late-arriving rows as
  // "new" and override the user's persisted collapse state.
  const allBlockCollapseIds = useMemo(
    () => grouped.flatMap((g) => g.items.map(itemId)),
    [grouped],
  );
  const seenBlocksRef = useRef<{ examId: string | null; ids: Set<string> }>({
    examId: null,
    ids: new Set(),
  });
  const pendingAddsRef = useRef(0);
  const markPendingAdd = useCallback(() => {
    pendingAddsRef.current += 1;
  }, []);
  useEffect(() => {
    if (!id) return;
    if (seenBlocksRef.current.examId !== id) {
      seenBlocksRef.current = { examId: id, ids: new Set() };
      pendingAddsRef.current = 0;
    }
    const seen = seenBlocksRef.current.ids;
    const fresh = allBlockCollapseIds.filter((bid) => !seen.has(bid));
    if (fresh.length === 0) return;
    fresh.forEach((bid) => seen.add(bid));
    // Only force-expand when the user explicitly added something. Otherwise
    // the ids are just being hydrated from the server — respect persisted
    // collapse state.
    if (pendingAddsRef.current > 0) {
      collapseApi.setManyCollapsed(fresh, false);
      pendingAddsRef.current = Math.max(0, pendingAddsRef.current - 1);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, allBlockCollapseIds.join("|")]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const handleDragEnd = (group: { items: BlockItem[] }) =>
    (event: DragEndEvent) => {
      const { active, over } = event;
      if (!over || active.id === over.id) return;
      const ids = group.items.map(itemId);
      const oldIndex = ids.indexOf(String(active.id));
      const newIndex = ids.indexOf(String(over.id));
      if (oldIndex < 0 || newIndex < 0) return;
      const newOrder = arrayMove(group.items, oldIndex, newIndex);
      void persistReorder(newOrder);
    };

  // Pure UI labels: a, b, c... per task within its section. Computed in
  // render — no memoization, no callbacks, no cache churn.
  const taskLetterById = (() => {
    const counts = new Map<string | null, number>();
    const out = new Map<string, string>();
    const sorted = (tasks ?? []).slice().sort((a, b) => a.position - b.position);
    for (const task of sorted) {
      const key = task.section_id ?? null;
      const i = counts.get(key) ?? 0;
      out.set(task.id, letterLabel(i));
      counts.set(key, i + 1);
    }
    return out;
  })();

  /**
   * Render a single block. When collapsed → lightweight TOC row. When
   * expanded → full editor card. Both paths share the same SortableItem so
   * DnD reordering works regardless of expansion state.
   */
  const renderBlockItem = (item: BlockItem) => {
    const id = itemId(item);
    const expanded = !collapseApi.isCollapsed(id);
    const onToggle = () => collapseApi.toggle(id);
    const scrollTargetId =
      item.kind === "task" ? `task-${item.task.id}` : undefined;

    return (
      <div key={id} id={scrollTargetId}>
      <SortableItem id={id}>
        {({ setNodeRef, style, isDragging, dragHandleProps }) => {
          if (!expanded) {
            // Build the row label + subtitle per block kind.
            let label: ReactNode = "";
            let subtitle: ReactNode = null;
            let points: number | null | undefined;
            let missingScore = false;
            let badgeText = "";
            let leadingIcon: ReactNode;
            if (item.kind === "task") {
              const TaskTypeIcon = taskTypeIcon(item.task.type);
              const letter = taskLetterById.get(item.task.id) ?? "";
              const prompt = item.task.prompt?.trim() ?? "";
              label = letter
                ? `${letter})`
                : "Untitled task";
              subtitle = prompt ? (
                <p className="text-sm leading-relaxed text-hestia-text-muted line-clamp-2">
                  {prompt}
                </p>
              ) : (
                <p className="text-sm italic text-hestia-text-muted/70">
                  Enter the task question…
                </p>
              );
              points = item.task.points ?? null;
              missingScore =
                item.task.points == null || item.task.points <= 0;
              badgeText = TASK_TYPE_LABELS[item.task.type];
              leadingIcon = (
                <TaskTypeIcon
                  size={14}
                  className="text-hestia-text-muted"
                  aria-hidden
                />
              );
            } else if (item.kind === "figure") {
              label = figureLabels.get(item.block.id) ?? "Figure";
              badgeText = "Figure";
              leadingIcon = (
                <ImageIcon
                  size={14}
                  className="text-hestia-text-muted"
                  aria-hidden
                />
              );
            } else {
              label = "Context";
              badgeText = "Context";
              leadingIcon = (
                <FileText
                  size={14}
                  className="text-hestia-text-muted"
                  aria-hidden
                />
              );
            }
            return (
              <BlockRow
                kind={item.kind}
                label={label}
                subtitle={subtitle}
                points={points}
                missingScore={missingScore}
                leadingIcon={leadingIcon}
                badge={
                  <Badge
                    variant="secondary"
                    className="bg-hestia-primary-muted/30 text-hestia-text-muted"
                  >
                    {badgeText}
                  </Badge>
                }
                onToggle={onToggle}
                setNodeRef={setNodeRef}
                style={style}
                isDragging={isDragging}
                dragHandleProps={dragHandleProps}
              />
            );
          }

          // Expanded: render the full editor card. Its own header chevron
          // toggles back to the collapsed row via the shared collapseApi.
          if (item.kind === "context") {
            return (
              <ContextBlockCard
                block={item.block}
                onToggleCollapsed={onToggle}
                onPatch={(patch) => patchBlock(item.block.id, patch)}
                onDelete={() => deleteBlock(item.block.id)}
                setNodeRef={setNodeRef}
                style={style}
                isDragging={isDragging}
                dragHandleProps={dragHandleProps}
              />
            );
          }
          if (item.kind === "figure") {
            return (
              <FigureBlockCard
                block={item.block}
                examId={exam!.id}
                displayLabel={
                  figureLabels.get(item.block.id) ?? "Figure"
                }
                onToggleCollapsed={onToggle}
                onDelete={() => deleteBlock(item.block.id)}
                setNodeRef={setNodeRef}
                style={style}
                isDragging={isDragging}
                dragHandleProps={dragHandleProps}
              />
            );
          }
          return (
            <TaskCard
              task={item.task}
              label={taskLetterById.get(item.task.id) ?? ""}
              collapsed={false}
              onToggleCollapsed={onToggle}
              onPatch={(patch) => patchTask(item.task.id, patch)}
              onDelete={() => deleteTask(item.task.id)}
              onDuplicate={() => duplicateTask(item.task)}
              onConvert={(toType) =>
                patchTask(item.task.id, convertTaskType(item.task, toType))
              }
              setNodeRef={setNodeRef}
              style={style}
              isDragging={isDragging}
              dragHandleProps={dragHandleProps}
            />
          );
        }}
      </SortableItem>
      </div>
    );
  };

  const confirmedSectionIds = useMemo(() => {
    const set = new Set<string>();
    for (const s of sections ?? []) {
      if (confirmApi.isConfirmed(s.id)) set.add(s.id);
    }
    return set;
  }, [sections, confirmApi]);

  const sectionEntries = useEditSectionEntries(
    sections ?? [],
    tasks ?? [],
    confirmedSectionIds,
  );

  // Resolve the currently visible section's tasks (drives the footer
  // progress button). Matches by carousel slug, which encodes section index
  // or "section-unassigned" for the orphan bucket.
  const currentGroup = useMemo(
    () => grouped.find((g) => g.slug === currentSectionId) ?? null,
    [grouped, currentSectionId],
  );
  const currentSectionTasks = useMemo(
    () => currentGroup?.tasks ?? [],
    [currentGroup],
  );
  const currentSectionRealId = currentGroup?.section?.id ?? "_unassigned";

  // "Ready" = every real section has all tasks scored AND the user has
  // confirmed it. The footer's Start Solving CTA gates on this, so missing
  // confirmation must keep the button in its earlier (advance-section) state.
  const allSectionsReady = useMemo(() => {
    const real = grouped.filter((g) => g.section);
    if (real.length === 0) return false;
    return real.every(
      (g) => isSectionReady(g.tasks) && confirmApi.isConfirmed(g.section!.id),
    );
  }, [grouped, confirmApi]);

  // Scroll-and-expand jump used by the footer progress button.
  const handleJumpToTask = useCallback(
    (taskId: string) => {
      const id = `task:${taskId}`;
      if (collapseApi.isCollapsed(id)) {
        collapseApi.setManyCollapsed([id], false);
      }
      // Defer to next frame so the expanded card is in the DOM.
      requestAnimationFrame(() => {
        const el = document.getElementById(`task-${taskId}`);
        el?.scrollIntoView({ behavior: "smooth", block: "center" });
      });
    },
    [collapseApi],
  );

  // Like handleJumpToTask, but also focuses the score input — used by the
  // "Score needs to be set" wayfinding indicator.
  const handleGoToScore = useCallback(
    (taskId: string) => {
      const id = `task:${taskId}`;
      if (collapseApi.isCollapsed(id)) {
        collapseApi.setManyCollapsed([id], false);
      }
      requestAnimationFrame(() => {
        document
          .getElementById(`task-${taskId}`)
          ?.scrollIntoView({ behavior: "smooth", block: "center" });
        // Focus after the smooth scroll settles so the caret lands cleanly.
        window.setTimeout(() => {
          const input = document.getElementById(
            `score-input-${taskId}`,
          ) as HTMLInputElement | null;
          input?.focus();
          input?.select();
        }, 300);
      });
    },
    [collapseApi],
  );

  // First task in the visible section still missing a score (drives the
  // wayfinding indicator). Null once the section is ready to confirm.
  const nextUnscoredTaskId = useMemo(() => {
    const sorted = currentSectionTasks
      .slice()
      .sort((a, b) => a.position - b.position);
    return (
      sorted.find((t) => t.points == null || t.points <= 0)?.id ?? null
    );
  }, [currentSectionTasks]);

  // Confirm current section and jump to the next unconfirmed one.
  const handleAdvanceSection = useCallback(() => {
    if (!currentGroup) return;
    const sid = currentSectionRealId;
    void confirmApi.confirm(sid);

    // If this was the final section (every real section now ready + confirmed),
    // skip the extra "Next section" hop and open Start Solving straight away.
    const finishesExam = grouped
      .filter((g) => g.section)
      .every((g) => {
        const gid = g.section!.id;
        return (
          isSectionReady(g.tasks) &&
          (gid === sid || confirmApi.isConfirmed(gid))
        );
      });
    if (finishesExam) {
      setStartSolvingOpen(true);
      return;
    }

    const idx = grouped.findIndex((g) => g.slug === currentGroup.slug);
    const findNextUnconfirmed = (from: number, to: number) => {
      for (let j = from; j < to; j++) {
        const next = grouped[j];
        const nextSid = next.section?.id ?? "_unassigned";
        if (nextSid === sid) continue;
        if (!confirmApi.isConfirmed(nextSid)) return next.slug;
      }
      return null;
    };
    const nextSlug =
      findNextUnconfirmed(idx + 1, grouped.length) ??
      findNextUnconfirmed(0, idx);
    if (nextSlug) setCurrentSectionId(nextSlug);
  }, [confirmApi, currentGroup, currentSectionRealId, grouped]);

  if (isLoading) {
    return <EditorLoadingView />;
  }

  if (!exam) {
    return (
      <div className="p-hestia-10 text-center">
        <p className="text-hestia-text-muted">404</p>
        <Link to="/exams" className="text-hestia-primary hover:underline">
          All exams
        </Link>
      </div>
    );
  }

  if (exam.status === "parsing") {
    return (
      <EvaluatingView
        title={exam.title || "Untitled exam"}
        kind="parsing"
        examId={exam.id}
        parsePhase={exam.parse_phase ?? null}
        onCancel={handleCancelProcessing}
      />
    );
  }

  if (exam.status === "evaluating") {
    return (
      <EvaluatingView
        title={exam.title || "Untitled exam"}
        kind="evaluating"
        examId={exam.id}
        onCancel={handleCancelProcessing}
      />
    );
  }

  if (exam.status === "grading" || exam.status === "finished") {
    if (evaluationStartedRef.current) {
      return (
        <EvaluatingView
          title={exam.title || "Untitled exam"}
          kind="evaluating"
          examId={exam.id}
          solveDone
          onContinue={() => navigate(`/exams/${exam.id}/grade`)}
        />
      );
    }
    return <Navigate to={`/exams/${exam.id}/grade`} replace />;
  }

  const isEmpty = (!sections || sections.length === 0) && (!tasks || tasks.length === 0);

  return (
    <div className="flex h-screen w-full flex-col overflow-hidden">
      <div className="flex min-h-0 flex-1">
        <SectionSidebar
          entries={sectionEntries}
          currentSectionId={currentSectionId}
          onSelectSection={handleSelectSection}
          onAddSection={() => addSection()}
          onDeleteSection={(entry: SectionEntry) =>
            setPendingDeleteSection({ id: entry.id, label: entry.fullLabel })
          }
          footerScore={`Total: ${totalPoints(tasks ?? [])} pt`}
          title={
            <InlineTitle value={exam.title} onSave={(v) => patchExam({ title: v })} />
          }
          titleTrailing={<SaveIndicator />}
        />
        <main className="relative flex min-w-0 flex-1 flex-col">
          {showInlineIntro && !introComplete && currentSectionId === "" && (
            <div
              aria-hidden="true"
              className="pointer-events-none absolute left-hestia-3 top-[6.25rem] z-20 inline-flex items-center gap-1.5 rounded-hestia-full border border-hestia-primary/30 bg-hestia-surface px-3 py-1.5 text-xs font-semibold text-hestia-primary shadow-hestia-md motion-safe:animate-pulse"
            >
              <ArrowLeft size={14} />
              Start here
            </div>
          )}
          <div ref={scrollRef} className="flex-1 overflow-y-auto">
            <div className="mx-auto w-full max-w-[900px] px-hestia-6 pb-hestia-8 pt-hestia-5">
              {isEmpty ? (
                <div className="space-y-hestia-4 py-hestia-10 text-center">
                  <button
                    type="button"
                    onClick={() => addSection()}
                    className="mx-auto inline-flex items-center gap-2 rounded-hestia-md border border-dashed border-hestia-border px-hestia-5 py-hestia-4 text-sm text-hestia-text-muted hover:border-hestia-primary hover:text-hestia-primary"
                  >
                    <Plus size={14} />
                    + Add a section
                  </button>
                  <div>
                    <AddTaskInline
                      variant="empty"
                      onAdd={(tp) => addTask(tp, 0, null)}
                    />
                  </div>
                </div>
              ) : (
                <CarouselView
                  exam={exam}
                  grouped={grouped}
                  sensors={sensors}
                  handleDragEnd={handleDragEnd}
                  renderBlockItem={renderBlockItem}
                  taskLetterById={taskLetterById}
                  lastPositionIn={lastPositionIn}
                  addTask={addTask}
                  addContextBlock={addContextBlock}
                  addFigureBlock={addFigureBlock}
                  patchSection={patchSection}
                  deleteSection={deleteSection}
                  collapseApi={collapseApi}
                  confirmApi={confirmApi}
                  currentId={currentSectionId}
                  onCurrentIdChange={setCurrentSectionId}
                  introPending={showInlineIntro && !introComplete && currentSectionId === ""}
                />
              )}
            </div>
          </div>
          {!isEmpty && (
            <ScoreNeededIndicator
              scrollRef={scrollRef}
              targetTaskId={nextUnscoredTaskId}
              onGoToScore={handleGoToScore}
            />
          )}
        </main>
      </div>

      <ExamEditFooter
        exam={exam}
        onSendToEvaluation={sendToEvaluation}
        solverModelId={solverModelId}
        currentSectionTasks={currentSectionTasks}
        taskLetterById={taskLetterById}
        allSectionsReady={allSectionsReady}
        onJumpToTask={handleJumpToTask}
        onAdvanceSection={handleAdvanceSection}
        startSolvingOpen={startSolvingOpen}
        onStartSolvingOpenChange={setStartSolvingOpen}
      />

      <AlertDialog
        open={pendingDeleteSection != null}
        onOpenChange={(open) => {
          if (!open) setPendingDeleteSection(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              Delete this section?
            </AlertDialogTitle>
            <AlertDialogDescription>
              Tasks in this section will become unassigned.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={(ev) => {
                ev.preventDefault();
                const target = pendingDeleteSection;
                setPendingDeleteSection(null);
                if (target) void deleteSection(target.id);
              }}
              className="bg-hestia-danger text-white hover:bg-hestia-danger/90"
            >
              Delete section
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
};

// ----------------------------------------------------------------------------
// CarouselView: focused, one-section-at-a-time editor flow.
// ----------------------------------------------------------------------------

interface CarouselViewProps {
  exam: Exam;
  grouped: Array<{
    section: Section | null;
    tasks: Task[];
    items: BlockItem[];
    slug: string;
  }>;
  sensors: ReturnType<typeof useSensors>;
  handleDragEnd: (g: { items: BlockItem[] }) => (e: DragEndEvent) => void;
  renderBlockItem: (item: BlockItem) => React.ReactNode;
  taskLetterById: Map<string, string>;
  lastPositionIn: (g: { tasks: Task[]; items: BlockItem[] }) => number;
  addTask: (type: TaskType, afterPosition: number, sectionId: string | null) => void;
  addContextBlock: (afterPosition: number, sectionId: string) => void;
  addFigureBlock: (afterPosition: number, sectionId: string) => void;
  patchSection: (sectionId: string, patch: Partial<Section>) => void;
  deleteSection: (sectionId: string) => void;
  collapseApi: ReturnType<typeof useItemCollapseState>;
  confirmApi: ReturnType<typeof useSectionConfirmations>;
  currentId: string;
  onCurrentIdChange: (id: string) => void;
  introPending: boolean;
}

const CarouselView = ({
  exam,
  grouped,
  sensors,
  handleDragEnd,
  renderBlockItem,
  taskLetterById,
  lastPositionIn,
  addTask,
  addContextBlock,
  addFigureBlock,
  patchSection,
  deleteSection,
  collapseApi,
  confirmApi,
  currentId,
  onCurrentIdChange,
  introPending,
}: CarouselViewProps) => {
  // Sticky id used in the carousel when there is no real section.
  const groupSectionId = (g: { section: Section | null }) =>
    g.section?.id ?? "_unassigned";

  // Scroll to top on mount so a stale URL hash doesn't leave the page mid-scroll.
  useEffect(() => {
    window.scrollTo(0, 0);
  }, []);

  const slides: CarouselSlide[] = [];

  for (let gi = 0; gi < grouped.length; gi++) {
    const g = grouped[gi];
    const sid = groupSectionId(g);
    const lastInGroup = lastPositionIn(g);
    const groupItemIds = g.items.map(itemId);
    const ready = isSectionReady(g.tasks);
    const isConfirmed = ready && confirmApi.isConfirmed(sid);

    const rowsList = (
      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        onDragEnd={handleDragEnd(g)}
      >
        <SortableContext
          items={groupItemIds}
          strategy={verticalListSortingStrategy}
        >
          <div className="space-y-hestia-3">
            {g.items.length === 0 && (
              <p className="px-hestia-2 py-hestia-3 text-center text-xs text-hestia-text-muted">
                Add at least one task to this section.
              </p>
            )}
            {g.items.map(renderBlockItem)}
          </div>
        </SortableContext>
      </DndContext>
    );

    const addBlockControl = (
      <AddTaskInline
        onAdd={(tp) => addTask(tp, lastInGroup, g.section?.id ?? null)}
        onAddContext={
          g.section
            ? () => addContextBlock(lastInGroup, g.section!.id)
            : undefined
        }
        onAddFigure={
          g.section
            ? () => addFigureBlock(lastInGroup, g.section!.id)
            : undefined
        }
      />
    );

    const content = g.section ? (
      <section id={g.slug} className="scroll-mt-12">
        <SectionLayout
          status={
            isConfirmed
              ? "confirmed"
              : ready
                ? "ready"
                : "draft"
          }
          title={
            <SectionTitleInput
              section={g.section}
              onPatch={(patch) => patchSection(g.section!.id, patch)}
            />
          }
          beforeFooter={addBlockControl}
        >
          {rowsList}
        </SectionLayout>
      </section>
    ) : (
      <section
        id={g.slug}
        className="scroll-mt-12 space-y-hestia-3 rounded-hestia-lg border border-dashed border-hestia-border/70 bg-hestia-bg/40 p-hestia-4"
      >
        <div className="flex items-center justify-between gap-hestia-2">
          <h2 className="font-body text-base font-semibold text-hestia-text">
            Unassigned tasks
          </h2>
          {addBlockControl}
        </div>
        {rowsList}
      </section>
    );

    slides.push({ id: g.slug, content });
  }

  if (introPending) {
    const isManual = exam.source === "manual";
    return (
      <div className="py-hestia-5">
        {isManual ? (
          <ManualIntroSlide />
        ) : (
          <IntroSlide />
        )}
      </div>
    );
  }

  return (
    <SectionCarousel
      slides={slides}
      currentId={currentId}
      onChange={onCurrentIdChange}
    />
  );
};

const ExamEdit = () => (
  <div className="h-screen overflow-hidden bg-hestia-bg text-hestia-text">
    <SaveStatusProvider>
      <ExamEditInner />
    </SaveStatusProvider>
  </div>
);

export default ExamEdit;
