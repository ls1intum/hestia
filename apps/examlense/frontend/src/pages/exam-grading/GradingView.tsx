import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ClipboardCheck, BotMessageSquare } from "lucide-react";
import { patchExam } from "@/lib/api/api-client";
import { useQueryClient } from "@tanstack/react-query";
import { tasksKey } from "@/hooks/data/use-exam";
import { useExamBundle } from "@/hooks/data/use-exam-bundle";
import { useTaskAnswers } from "@/hooks/data/use-task-answers";
import { useTaskGrades } from "@/hooks/data/use-task-grades";
import { useExamLearningGoals, examLearningGoalsKey } from "@/hooks/data/use-learning-goals";
import { useExamRealtime } from "@/hooks/data/use-exam-realtime";
import type { LearningGoalResponse } from "@/lib/learning-goals/learning-goals";
import { type TaskGoalDisplay } from "@/components/shared/exam-content/read-only/ReadOnlyTaskCard";
import { ReadOnlyQuestionBlock } from "@/pages/exam-grading/components/ReadOnlyQuestionBlock";
import { ReadOnlyContextBlock } from "@/components/shared/exam-content/read-only/ReadOnlyContextBlock";
import { ReadOnlyFigureBlock } from "@/components/shared/exam-content/read-only/ReadOnlyFigureBlock";
import { TaskGradingPanel } from "@/pages/exam-grading/components/TaskGradingPanel";
import { GradingProgressButton } from "@/pages/exam-grading/components/GradingProgressButton";
import { ChromeFooter } from "@/components/shared/chrome/ChromeFooter";
import { ChromeUtilityCluster } from "@/components/shared/chrome/ChromeUtilityCluster";
import { StaticTitle } from "@/components/shared/chrome/InlineTitle";
import {
  SectionSidebar,
  useGradingSectionEntries,
} from "@/components/shared/exam-content/SectionSidebar";
import { EditorLoadingView } from "@/components/shared/exam-content/EditorLoadingView";
import { SectionLayout } from "@/components/shared/exam-content/SectionLayout";
import {
  SectionCarousel,
  type CarouselSlide,
} from "@/components/shared/exam-content/SectionCarousel";
import { type Task } from "@/lib/exam/exam-helpers";
import {
  useSectionGroups,
  useCurrentSectionId,
} from "@/hooks/ui/use-section-groups";
import { useSectionScrollMemory } from "@/hooks/ui/use-section-scroll-memory";
import {
  effectiveScore,
  examTotals,
  type TaskAnswer,
  type TaskGrade,
} from "@/lib/grading/grading";
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

interface Props {
  examId: string;
}

export const GradingView = ({ examId }: Props) => {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { exam, tasks, sections, blocks, isLoading } = useExamBundle(examId);
  const { data: answers } = useTaskAnswers(examId);
  const { data: grades } = useTaskGrades(examId);
  const { data: learningGoals, isError: goalsError } = useExamLearningGoals(examId);

  // Learning goals can finish generating while this view is open — refresh
  // the tasks (goal ids) and resolved goals on the backend's `tasks` event.
  useExamRealtime(examId, {
    onTasks: () => {
      qc.invalidateQueries({ queryKey: tasksKey(examId) });
      qc.invalidateQueries({ queryKey: examLearningGoalsKey(examId) });
    },
  });

  const goalsById = useMemo(() => {
    const m = new Map<number, LearningGoalResponse>();
    (learningGoals ?? []).forEach((g) => m.set(g.id, g));
    return m;
  }, [learningGoals]);

  /** Resolve a task's goal ids; falls back to id-only placeholders when LGH is down. */
  const goalsForTask = (task: Task): TaskGoalDisplay[] =>
    (task.learning_goal_ids ?? []).map(
      (gid) => goalsById.get(gid) ?? { id: gid },
    );

  const anyTaskHasGoals = useMemo(
    () => (tasks ?? []).some((t) => (t.learning_goal_ids ?? []).length > 0),
    [tasks],
  );

  const answersById = useMemo(() => {
    const m = new Map<string, TaskAnswer>();
    (answers ?? []).forEach((a) => m.set(a.task_id, a));
    return m;
  }, [answers]);

  const gradesById = useMemo(() => {
    const m = new Map<string, TaskGrade>();
    (grades ?? []).forEach((g) => m.set(g.task_id, g));
    return m;
  }, [grades]);

  const totals = useMemo(
    () => examTotals(tasks ?? [], gradesById, answersById),
    [tasks, gradesById, answersById],
  );

  const pendingByTaskId = useMemo(() => {
    const m = new Map<string, boolean>();
    for (const task of tasks ?? []) {
      const eff = effectiveScore(task, gradesById.get(task.id), answersById.get(task.id));
      m.set(task.id, eff.score == null);
    }
    return m;
  }, [tasks, gradesById, answersById]);

  const totalTasks = (tasks ?? []).length;
  const gradedTasks = totalTasks - totals.pending;
  const allGraded = totalTasks > 0 && totals.pending === 0;
  const [finishing, setFinishing] = useState(false);
  const [finishOpen, setFinishOpen] = useState(false);

  // Briefly pulse the footer progress bar when graded count goes UP.
  const prevGradedRef = useRef(gradedTasks);
  const [progressFlash, setProgressFlash] = useState(false);
  useEffect(() => {
    if (gradedTasks > prevGradedRef.current) {
      setProgressFlash(true);
      const id = window.setTimeout(() => setProgressFlash(false), 800);
      prevGradedRef.current = gradedTasks;
      return () => window.clearTimeout(id);
    }
    prevGradedRef.current = gradedTasks;
  }, [gradedTasks]);

  const progressPct =
    totalTasks > 0 ? Math.round((gradedTasks / totalTasks) * 100) : 0;

  const finishGrading = async () => {
    setFinishing(true);
    try {
      await patchExam(examId, { status: "finished" });
      await qc.invalidateQueries({ queryKey: ["exam", examId] });
      navigate(`/exams/${examId}/results`, { replace: true });
    } finally {
      setFinishing(false);
    }
  };

  // Group tasks + blocks by section (grading drops sections with no items).
  const { grouped, taskLetterById, figureLabels } = useSectionGroups(
    sections,
    tasks,
    blocks,
    { includeEmpty: false },
  );

  const [currentId, setCurrentId] = useCurrentSectionId(grouped);

  // The scrolling content viewport. Remembers each section's scroll offset and
  // restores it on return, so switching sections doesn't share one offset.
  const scrollRef = useSectionScrollMemory<HTMLDivElement>(currentId);

  const sectionEntries = useGradingSectionEntries(
    sections ?? [],
    tasks ?? [],
    pendingByTaskId,
    gradesById,
    answersById,
  );

  // Tasks of the currently visible section — drives the footer navigator.
  const currentGroup = grouped.find((g) => g.slug === currentId);
  const currentSectionTasks = currentGroup?.tasks ?? [];

  /** Scroll a task into view and focus its score input (mirrors the editor). */
  const jumpToGradingTask = (taskId: string) => {
    const el = document.getElementById(`grading-task-${taskId}`);
    if (!el) return;
    el.scrollIntoView({ behavior: "smooth", block: "center" });
    window.setTimeout(() => {
      const input = el.querySelector<HTMLInputElement>("[data-score-input]");
      input?.focus();
      input?.select();
    }, 300);
  };

  /** Advance to the next section (in order, wrapping) that still has ungraded tasks. */
  const handleAdvanceGradingSection = () => {
    if (grouped.length === 0) return;
    const currentIdx = grouped.findIndex((g) => g.slug === currentId);
    const start = currentIdx === -1 ? 0 : currentIdx;
    for (let i = 1; i <= grouped.length; i += 1) {
      const g = grouped[(start + i) % grouped.length];
      if (g.slug === currentId) continue;
      if (g.tasks.some((tk) => pendingByTaskId.get(tk.id))) {
        setCurrentId(g.slug);
        return;
      }
    }
  };

  const slides: CarouselSlide[] = grouped.map((g) => {
    const isUnassigned = !g.section;
    const sectionPending = g.tasks.filter((tk) =>
      pendingByTaskId.get(tk.id),
    ).length;
    const sectionComplete = sectionPending === 0;
    const sectionGraded = g.tasks.length - sectionPending;
    const sectionTitle =
      g.section?.name?.trim() ||
      (isUnassigned
        ? "Unassigned tasks"
        : "Untitled section");
    const sectionTitleNode = (
      <h2 className="truncate font-body text-base font-semibold text-hestia-text">
        {sectionTitle}
      </h2>
    );
    const sectionItems = g.items.map((item) => {
      if (item.kind === "context") {
        return (
          <ReadOnlyContextBlock
            key={`c-${item.block.id}`}
            block={item.block}
          />
        );
      }
      if (item.kind === "figure") {
        return (
          <ReadOnlyFigureBlock
            key={`f-${item.block.id}`}
            block={item.block}
            displayLabel={
              figureLabels.get(item.block.id) ??
              "Figure"
            }
          />
        );
      }
      const task: Task = item.task;
      const label = taskLetterById.get(task.id) ?? "";
      return (
        <div key={`t-${task.id}`} id={`grading-task-${task.id}`}>
          <ReadOnlyQuestionBlock
            task={task}
            label={label}
            goals={goalsForTask(task)}
          />
          {/* Connector tying the question to its AI answer below it. */}
          <div className="flex flex-col items-center" aria-hidden>
            <span className="h-hestia-2 w-px bg-hestia-border" />
            <span className="flex h-7 w-7 items-center justify-center rounded-full border border-hestia-border bg-hestia-surface text-hestia-text-muted shadow-hestia-sm">
              <BotMessageSquare size={14} />
            </span>
            <span className="h-hestia-2 w-px bg-hestia-border" />
          </div>
          <TaskGradingPanel
            task={task}
            examId={examId}
            answer={answersById.get(task.id)}
            grade={gradesById.get(task.id)}
          />
        </div>
      );
    });

    return {
      id: g.slug,
      content: (
        <section id={g.slug} className="scroll-mt-12">
          <SectionLayout
            status={sectionComplete ? "confirmed" : "draft"}
            title={sectionTitleNode}
            progress={{ done: sectionGraded, total: g.tasks.length }}
          >
            {sectionItems}
          </SectionLayout>
        </section>
      ),
    };
  });

  if (isLoading) {
    return <EditorLoadingView />;
  }
  if (!exam) return null;

  return (
    <div className="flex h-screen w-full flex-col overflow-hidden">
      <div className="flex min-h-0 flex-1">
        <SectionSidebar
          entries={sectionEntries}
          currentSectionId={currentId}
          onSelectSection={setCurrentId}
          footerScore={`${Number(totals.earned.toFixed(2))} / ${totals.max} pt`}
          title={<StaticTitle value={exam.title} />}
        />
        <main className="flex min-w-0 flex-1 flex-col">
          <div className="w-full border-b border-hestia-grading/30 bg-hestia-grading/10 text-hestia-grading">
            <div className="flex w-full items-center gap-hestia-3 px-hestia-6 py-hestia-2">
              <ClipboardCheck size={16} className="shrink-0" />
              <span className="hestia-eyebrow">Grading Mode</span>
            </div>
          </div>

          <div ref={scrollRef} className="flex-1 overflow-y-auto">
            <div className="mx-auto w-full max-w-[900px] px-hestia-6 pb-hestia-8 pt-hestia-5">
              {goalsError && anyTaskHasGoals && (
                <p className="mb-hestia-3 rounded-hestia-md border border-hestia-border bg-hestia-primary-muted/10 px-hestia-3 py-hestia-2 text-sm text-hestia-text-muted">
                  Learning goals could not be loaded from LearningGoalHub — showing
                  goal ids only.
                </p>
              )}
              {grouped.length === 0 ? (
                <p className="py-hestia-10 text-center text-sm text-hestia-text-muted">
                  No tasks to grade.
                </p>
              ) : (
                <SectionCarousel
                  slides={slides}
                  currentId={currentId}
                  onChange={setCurrentId}
                />
              )}
            </div>
          </div>
        </main>
      </div>

      <ChromeFooter
        left={
          <div className="flex items-center gap-hestia-3">
            <span className="text-xs font-medium tabular-nums text-hestia-text-muted">
              {`${gradedTasks}/${totalTasks} graded`}
              {totalTasks > 0 && (
                <span className="ml-2 text-hestia-text/60">· {progressPct}%</span>
              )}
            </span>
            <div className="relative hidden h-2.5 w-48 overflow-hidden rounded-full bg-hestia-text/10 ring-1 ring-hestia-border/60 sm:block">
              <div
                className="relative h-full overflow-hidden rounded-full bg-hestia-primary transition-[width] duration-500 ease-out"
                style={{ width: `${progressPct}%` }}
              >
                {progressFlash && (
                  <span
                    key={`shimmer-${gradedTasks}`}
                    aria-hidden
                    className="absolute inset-y-0 left-0 w-1/2 animate-progress-shimmer bg-gradient-to-r from-transparent via-white/70 to-transparent"
                  />
                )}
              </div>
            </div>
          </div>
        }
        center={
          <GradingProgressButton
            currentSectionTasks={currentSectionTasks}
            pendingByTaskId={pendingByTaskId}
            taskLetterById={taskLetterById}
            allGraded={allGraded}
            onJumpToTask={jumpToGradingTask}
            onAdvanceSection={handleAdvanceGradingSection}
            onFinish={() => setFinishOpen(true)}
          />
        }
        right={<ChromeUtilityCluster helpVariant="grading" />}
      />

      <AlertDialog open={finishOpen} onOpenChange={setFinishOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Finish grading?</AlertDialogTitle>
            <AlertDialogDescription>This takes you to the results page for the full evaluation and analysis. You can return and adjust grades anytime.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={finishGrading}
              disabled={finishing}
              className="bg-[hsl(152_50%_30%)] text-white hover:bg-[hsl(152_50%_26%)]"
            >
              Finish grading
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
};

export default GradingView;
