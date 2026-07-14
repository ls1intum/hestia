import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ClipboardCheck, CheckCircle2 } from "lucide-react";
import { patchExam } from "@/lib/api/api-client";
import { useQueryClient } from "@tanstack/react-query";
import { useExam, useTasks, tasksKey } from "@/hooks/data/use-exam";
import { useSections, useSectionBlocks } from "@/hooks/data/use-sections";
import { useTaskAnswers } from "@/hooks/data/use-task-answers";
import { useTaskGrades } from "@/hooks/data/use-task-grades";
import { useExamLearningGoals, examLearningGoalsKey } from "@/hooks/data/use-learning-goals";
import { subscribeExam } from "@/lib/api/sse";
import type { LearningGoalResponse } from "@/lib/learning-goals/learning-goals";
import { type TaskGoalDisplay } from "@/components/shared/exam-content/read-only/ReadOnlyTaskCard";
import { ReadOnlyQuestionBlock } from "@/pages/exam-grading/components/ReadOnlyQuestionBlock";
import { ReadOnlyContextBlock } from "@/components/shared/exam-content/read-only/ReadOnlyContextBlock";
import { ReadOnlyFigureBlock } from "@/components/shared/exam-content/read-only/ReadOnlyFigureBlock";
import { TaskGradingPanel } from "@/pages/exam-grading/components/TaskGradingPanel";
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
import {
  figureLabelsForBlocks,
  letterLabel,
  mergeSectionItems,
  type Section,
  type SectionBlock,
  type Task,
} from "@/lib/exam/exam-helpers";
import {
  effectiveScore,
  examTotals,
  type TaskAnswer,
  type TaskGrade,
} from "@/lib/grading/grading";
import { Button } from "@/components/ui/button";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";

interface Props {
  examId: string;
}

export const GradingView = ({ examId }: Props) => {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { data: exam, isLoading: examLoading } = useExam(examId);
  const { data: tasks, isLoading: tasksLoading } = useTasks(examId);
  const { data: sections, isLoading: sectionsLoading } = useSections(examId);
  const { data: blocks, isLoading: blocksLoading } = useSectionBlocks(examId);
  const { data: answers } = useTaskAnswers(examId);
  const { data: grades } = useTaskGrades(examId);
  const { data: learningGoals, isError: goalsError } = useExamLearningGoals(examId);

  // Learning goals can finish generating while this view is open — refresh
  // the tasks (goal ids) and resolved goals on the backend's `tasks` event.
  useEffect(() => {
    return subscribeExam(examId, {
      onTasks: () => {
        qc.invalidateQueries({ queryKey: tasksKey(examId) });
        qc.invalidateQueries({ queryKey: examLearningGoalsKey(examId) });
      },
    });
  }, [examId, qc]);

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

  const grouped = useMemo(() => {
    const sortedSections = (sections ?? []).slice().sort((a, b) => a.position - b.position);
    const allSections: (Section | null)[] = [...sortedSections, null];
    const taskList = tasks ?? [];
    const blockList = blocks ?? [];
    const sectionIndexById = new Map<string, number>();
    sortedSections.forEach((s, i) => sectionIndexById.set(s.id, i));
    return allSections
      .map((sec) => {
        const sId = sec?.id ?? null;
        const sectionTasks = taskList.filter((tk) => (tk.section_id ?? null) === sId);
        const sectionBlocks: SectionBlock[] = sec
          ? blockList.filter((b) => b.section_id === sec.id)
          : [];
        const slug = sec
          ? `section-${(sectionIndexById.get(sec.id) ?? 0) + 1}`
          : "section-unassigned";
        return {
          section: sec,
          tasks: sectionTasks,
          items: mergeSectionItems(sectionTasks, sectionBlocks),
          slug,
        };
      })
      .filter((g) => g.items.length > 0);
  }, [tasks, sections, blocks]);

  const taskLetterById = useMemo(() => {
    const m = new Map<string, string>();
    grouped.forEach((g) => {
      g.tasks
        .slice()
        .sort((a, b) => a.position - b.position)
        .forEach((task, i) => m.set(task.id, letterLabel(i)));
    });
    return m;
  }, [grouped]);

  const figureLabels = useMemo(
    () => figureLabelsForBlocks(sections, blocks),
    [sections, blocks],
  );

  const [currentId, setCurrentId] = useState(() => {
    if (typeof window === "undefined") return "";
    return window.location.hash.replace(/^#/, "");
  });

  useEffect(() => {
    const validIds = new Set(grouped.map((g) => g.slug));
    if (!currentId || !validIds.has(currentId)) {
      setCurrentId(grouped[0]?.slug ?? "");
    }
  }, [grouped, currentId]);

  const sectionEntries = useGradingSectionEntries(
    sections ?? [],
    tasks ?? [],
    pendingByTaskId,
    gradesById,
    answersById,
  );

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
        <div key={`t-${task.id}`} className="space-y-hestia-2">
          <ReadOnlyQuestionBlock
            task={task}
            label={label}
            goals={goalsForTask(task)}
          />
          <TaskGradingPanel
            task={task}
            examId={examId}
            answer={answersById.get(task.id)}
            grade={gradesById.get(task.id)}
            label={label}
            graded={!pendingByTaskId.get(task.id)}
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

  if (examLoading || tasksLoading || sectionsLoading || blocksLoading) {
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
          <div className="w-full border-b border-hestia-primary/30 bg-hestia-primary/10 text-hestia-primary">
            <div className="flex w-full items-center gap-hestia-3 px-hestia-6 py-hestia-2">
              <ClipboardCheck size={16} className="shrink-0" />
              <span className="hestia-eyebrow">Grading Mode</span>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto">
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
        right={
          <>
            {allGraded && (
              <AlertDialog>
                <AlertDialogTrigger asChild>
                  <Button
                    size="sm"
                    disabled={finishing}
                    className="gap-1 bg-hestia-success text-white hover:bg-hestia-success/90"
                  >
                    <CheckCircle2 size={14} />
                    Finish Grading
                  </Button>
                </AlertDialogTrigger>
                <AlertDialogContent>
                  <AlertDialogHeader>
                    <AlertDialogTitle>Finish grading?</AlertDialogTitle>
                    <AlertDialogDescription>Once you finish, the grading cannot be edited anymore. This action is permanent.</AlertDialogDescription>
                  </AlertDialogHeader>
                  <AlertDialogFooter>
                    <AlertDialogCancel>Cancel</AlertDialogCancel>
                    <AlertDialogAction onClick={finishGrading} disabled={finishing}>
                      Finish grading
                    </AlertDialogAction>
                  </AlertDialogFooter>
                </AlertDialogContent>
              </AlertDialog>
            )}
            <ChromeUtilityCluster helpVariant="grading" />
          </>
        }
      />
    </div>
  );
};

export default GradingView;
