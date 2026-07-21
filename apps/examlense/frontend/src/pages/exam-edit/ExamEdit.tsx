import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams, Navigate, useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Plus } from "lucide-react";
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
  listAnswers,
  cancelExam,
} from "@/lib/api/api-client";
import { examKey, tasksKey } from "@/hooks/data/use-exam";
import { sectionsKey, blocksKey } from "@/hooks/data/use-sections";
import { useExamBundle } from "@/hooks/data/use-exam-bundle";
import { useExamRealtime } from "@/hooks/data/use-exam-realtime";
import { useExamMutations } from "@/pages/exam-edit/use-exam-mutations";
import { SaveStatusProvider, useSaveStatus, SaveIndicator } from "@/pages/exam-edit/components/SaveStatus";
import { useToast } from "@/hooks/ui/use-toast";
import { examLearningGoalsKey } from "@/hooks/data/use-learning-goals";
import { AddTaskInline } from "@/pages/exam-edit/components/AddTaskInline";
import { SectionLayout } from "@/components/shared/exam-content/SectionLayout";
import { SectionTitleInput } from "@/pages/exam-edit/components/SectionTitleInput";
import { useItemCollapseState } from "@/hooks/ui/use-item-collapse-state";
import { BlockItem as BlockItemComponent } from "@/pages/exam-edit/components/BlockItem";
import { ExamEditFooter } from "@/pages/exam-edit/components/ExamEditFooter";
import { ScoreNeededIndicator } from "@/pages/exam-edit/components/ScoreNeededIndicator";
import { InlineTitle } from "@/components/shared/chrome/InlineTitle";
import { DEFAULT_SOLVER_MODEL_ID } from "@/lib/exam/llm-models";
import { solveExam } from "@/lib/api/api-solve";
import { EvaluatingView } from "@/pages/exam-edit/components/EvaluatingView";
import { EditorLoadingView } from "@/components/shared/exam-content/EditorLoadingView";
import { WayfindingPill } from "@/components/shared/WayfindingPill";
import { retryParse } from "@/lib/parsing/retry-parse";
import { retryEvaluation } from "@/lib/exam/retry-evaluation";
import { isParseFailure } from "@/lib/exam/exam-helpers";
import { SectionCarousel, type CarouselSlide } from "@/components/shared/exam-content/SectionCarousel";
import { IntroSlide } from "@/pages/exam-edit/components/IntroSlide";
import { ManualIntroSlide } from "@/pages/exam-edit/components/ManualIntroSlide";
import { useSectionConfirmations } from "@/hooks/data/use-section-confirmations";
import { useSectionMissingContent } from "@/hooks/data/use-section-missing-content";
import {
  blockDomId,
  examModePath,
  examModeSlug,
  isSectionReady,
  itemId,
  taskMissingScore,
  totalPoints,
  type BlockItem,
  type Exam,
  type Section,
  type Task,
  type TaskType,
} from "@/lib/exam/exam-helpers";
import {
  useSectionGroups,
  useCurrentSectionId,
} from "@/hooks/ui/use-section-groups";
import {
  SectionSidebar,
  useEditSectionEntries,
  type SectionEntry,
} from "@/components/shared/exam-content/SectionSidebar";
import { ConfirmDeleteDialog } from "@/components/shared/exam-content/ConfirmDeleteDialog";

const ExamEditInner = () => {
  const { id } = useParams<{ id: string }>();
  const qc = useQueryClient();
  const { toast } = useToast();
  const { setSaving, setSaved, setError } = useSaveStatus();
  const { exam, tasks, sections, blocks, isLoading } = useExamBundle(id);

  const solverModelId =
    exam?.solver_model ?? DEFAULT_SOLVER_MODEL_ID;

  // Keep the exam in sync if its status changes server-side (e.g. background
  // evaluation flips it back to a non-evaluating state).
  useExamRealtime(id, {
    // The SSE `exam` event carries no payload, so we can't diff status here.
    // Invalidate the exam and its child collections together: when the
    // background pipeline transitions out of a long-running phase
    // (parsing/evaluating/grading), the child collections were empty at first
    // load, and refetching them renders the freshly created
    // sections/tasks/blocks without a manual refresh.
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

  // No failure snackbar here: a failed exam always renders the full-screen error
  // splash below (with its own retry), so a toast would just double the signal.
  // The dashboard toasts instead (useParseFailureToasts), where there's no splash.

  const handleRetryParse = useCallback(() => {
    if (exam) retryParse(exam, qc);
  }, [exam, qc]);

  const handleRetryEvaluation = useCallback(() => {
    if (exam) retryEvaluation(exam, qc);
  }, [exam, qc]);

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

  // Group tasks + context blocks by section (editor keeps empty sections).
  // taskLetterById → a/b/c labels; figureLabels → "Figure {section}.{index}".
  const { grouped, taskLetterById, figureLabels } = useSectionGroups(
    sections,
    tasks,
    blocks,
    { includeEmpty: true },
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
  // Gate on the bundle having fully loaded: on first load `sections` can settle
  // before `tasks`, which would briefly make every section look empty and
  // unconfirm the entire exam.
  useEffect(() => {
    if (isLoading) return;
    if (!tasks || !sections) return;
    for (const g of grouped) {
      const sid = g.section?.id;
      if (!sid) continue;
      if (!confirmApiRef.current.isConfirmed(sid)) continue;
      if (!isSectionReady(g.tasks)) {
        void confirmApiRef.current.unconfirm(sid);
      }
    }
  }, [grouped, tasks, sections, isLoading]);

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

  const [currentSectionId, setCurrentSectionId] = useCurrentSectionId(grouped, {
    introGate: showInlineIntro && !introComplete,
  });

  const markIntroComplete = useCallback(() => {
    if (introKey) localStorage.setItem(introKey, "1");
    setIntroComplete(true);
  }, [introKey]);

  const handleSelectSection = useCallback(
    (slug: string) => {
      if (showInlineIntro && !introComplete) markIntroComplete();
      setCurrentSectionId(slug);
    },
    [introComplete, markIntroComplete, showInlineIntro, setCurrentSectionId],
  );

  // Dismiss the first-open visual guide and jump to the first real section.
  const handleStartReview = useCallback(() => {
    const first = grouped[0]?.slug;
    if (first) handleSelectSection(first);
  }, [grouped, handleSelectSection]);

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

  // CRUD/mutation layer (optimistic writes + save-status + unconfirm-on-edit).
  const {
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
  } = useExamMutations(id, {
    exam,
    tasks,
    blocks,
    sections,
    confirmApiRef,
    markPendingAdd,
  });

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

  const renderBlockItem = (item: BlockItem) => (
    <BlockItemComponent
      key={itemId(item)}
      item={item}
      collapseApi={collapseApi}
      figureLabels={figureLabels}
      taskLetterById={taskLetterById}
      examId={exam!.id}
      onPatchBlock={patchBlock}
      onDeleteBlock={deleteBlock}
      onPatchTask={patchTask}
      onDeleteTask={deleteTask}
      onDuplicateTask={duplicateTask}
    />
  );

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

  // Expand the target task and scroll it into view. `focusScore` also focuses
  // the score input after the scroll settles — used by the "Score needs to be
  // set" wayfinding indicator; the plain jump drives the footer progress button.
  // Expand the collapsed item (if needed) and scroll its DOM node into view,
  // then run an optional follow-up once the node is in the DOM. Shared core of
  // both the task-score jump and the content-missing jump.
  const scrollToBlock = useCallback(
    (collapseId: string, domId: string, after?: () => void) => {
      if (collapseApi.isCollapsed(collapseId)) {
        collapseApi.setManyCollapsed([collapseId], false);
      }
      // Defer to next frame so the expanded card is in the DOM.
      requestAnimationFrame(() => {
        document
          .getElementById(domId)
          ?.scrollIntoView({ behavior: "smooth", block: "center" });
        after?.();
      });
    },
    [collapseApi],
  );

  const jumpToTask = useCallback(
    (taskId: string, { focusScore = false }: { focusScore?: boolean } = {}) => {
      scrollToBlock(
        `task:${taskId}`,
        `task-${taskId}`,
        focusScore
          ? () => {
              // Focus after the smooth scroll settles so the caret lands cleanly.
              window.setTimeout(() => {
                const input = document.getElementById(
                  `score-input-${taskId}`,
                ) as HTMLInputElement | null;
                input?.focus();
                input?.select();
              }, 300);
            }
          : undefined,
      );
    },
    [scrollToBlock],
  );

  // Blocks in the visible section missing content (empty prompt/context/figure).
  // Gates confirmation and drives the footer "Content missing" state.
  const { missingItems, hasMissing: hasMissingContent } =
    useSectionMissingContent(currentGroup?.items);

  // Expand + scroll to any block (task, context, or figure) — generalises
  // jumpToTask for the "Content missing" wayfinding.
  const jumpToItem = useCallback(
    (item: BlockItem) => scrollToBlock(itemId(item), blockDomId(item)),
    [scrollToBlock],
  );

  const jumpToMissingContent = useCallback(() => {
    if (missingItems[0]) jumpToItem(missingItems[0]);
  }, [missingItems, jumpToItem]);

  // First task in the visible section still missing a score (drives the
  // wayfinding indicator). Null once the section is ready to confirm.
  const nextUnscoredTaskId = useMemo(() => {
    const sorted = currentSectionTasks
      .slice()
      .sort((a, b) => a.position - b.position);
    return sorted.find((t) => taskMissingScore(t))?.id ?? null;
  }, [currentSectionTasks]);

  // Confirm current section and jump to the next unconfirmed one.
  const handleAdvanceSection = useCallback(() => {
    if (!currentGroup) return;
    // Never confirm a section that still has blocks missing content — the
    // footer button surfaces the "Content missing" state instead, but guard
    // here too for any other caller.
    if (hasMissingContent) return;
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
  }, [confirmApi, currentGroup, currentSectionRealId, grouped, hasMissingContent, setCurrentSectionId]);

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
        pageCount={exam.page_count}
        parseStartedAt={exam.parse_started_at}
        onCancel={handleCancelProcessing}
      />
    );
  }

  // Failed: show the splash in its error state (icon + message + retry) instead
  // of falling through to the misleading empty editor. A parse failure offers a
  // re-parse; an evaluation failure (solve error / cancel on a parsed or manual
  // exam) offers a re-solve — never a re-parse, which would overwrite structure.
  if (exam.status === "failed") {
    const parseFailed = isParseFailure(exam);
    return (
      <EvaluatingView
        title={exam.title || "Untitled exam"}
        kind="parsing"
        variant="error"
        errorMessage={exam.parse_error}
        errorHeading={parseFailed ? "Parsing failed" : "Evaluation failed"}
        retryLabel={parseFailed ? "Retry parsing" : "Retry evaluation"}
        onRetry={parseFailed ? handleRetryParse : handleRetryEvaluation}
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

  // Editing is only valid for pre-grading statuses. Once an exam has moved on, /edit
  // redirects to its canonical mode (grading → /grade, finished → /results) rather
  // than forcing it back into the editor.
  if (examModeSlug(exam.status) !== "edit") {
    // Just-completed solve from within the editor: offer the hand-off to grading.
    if (evaluationStartedRef.current && exam.status === "grading") {
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
    return <Navigate to={examModePath(exam.id, exam.status)} replace />;
  }

  const isEmpty = (!sections || sections.length === 0) && (!tasks || tasks.length === 0);
  const introPending =
    showInlineIntro && !introComplete && currentSectionId === "";

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
          {introPending && (
            <WayfindingPill
              tone="primary"
              label="Start here"
              icon={<ArrowLeft size={14} />}
              onClick={handleStartReview}
              className="absolute left-hestia-3 top-[6.25rem] z-20 cursor-pointer motion-safe:animate-pulse"
            />
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
                  introPending={introPending}
                />
              )}
            </div>
          </div>
          {!isEmpty && (
            <ScoreNeededIndicator
              scrollRef={scrollRef}
              targetTaskId={nextUnscoredTaskId}
              onGoToScore={(taskId) => jumpToTask(taskId, { focusScore: true })}
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
        isIntro={!isEmpty && introPending}
        onStartReview={handleStartReview}
        hasMissingContent={hasMissingContent}
        onJumpToMissingContent={jumpToMissingContent}
        onJumpToTask={jumpToTask}
        onAdvanceSection={handleAdvanceSection}
        startSolvingOpen={startSolvingOpen}
        onStartSolvingOpenChange={setStartSolvingOpen}
      />

      <ConfirmDeleteDialog
        open={pendingDeleteSection != null}
        onOpenChange={(open) => {
          if (!open) setPendingDeleteSection(null);
        }}
        title="Delete this section?"
        description="Tasks in this section will become unassigned."
        onConfirm={(ev) => {
          ev.preventDefault();
          const target = pendingDeleteSection;
          setPendingDeleteSection(null);
          if (target) void deleteSection(target.id);
        }}
        confirmLabel="Delete section"
      />
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
