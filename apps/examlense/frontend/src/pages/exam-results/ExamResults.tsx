import { useMemo, useState } from "react";
import { Navigate, useNavigate, useParams } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { BarChart3, CheckCircle2, GraduationCap, ListChecks, PencilLine, Table2 } from "lucide-react";
import { useExam, useTasks, examKey } from "@/hooks/data/use-exam";
import { patchExam } from "@/lib/api/api-client";
import { useSections, useSectionBlocks } from "@/hooks/data/use-sections";
import { useTaskAnswers } from "@/hooks/data/use-task-answers";
import { useTaskGrades } from "@/hooks/data/use-task-grades";
import { EditorLoadingView } from "@/components/shared/exam-content/EditorLoadingView";
import { ResilienceOverview } from "@/pages/exam-results/components/ResilienceOverview";
import { ByQuestionTypeCard } from "@/pages/exam-results/components/ByQuestionTypeCard";
import { FiguresComparisonCard } from "@/pages/exam-results/components/FiguresComparisonCard";
import { TaskBreakdownTable } from "@/pages/exam-results/components/TaskBreakdownTable";
import { TaskScoreBarChart } from "@/pages/exam-results/components/TaskScoreBarChart";
import { LearningGoalsCard } from "@/pages/exam-results/components/LearningGoalsCard";
import { AllTasksList } from "@/pages/exam-results/components/AllTasksList";
import { ScoreBar } from "@/pages/exam-results/components/ScoreBar";
import {
  ResultsSidebar,
  type ResultsViewItem,
} from "@/pages/exam-results/components/ResultsSidebar";
import { ChromeFooter } from "@/components/shared/chrome/ChromeFooter";
import { ChromeUtilityCluster } from "@/components/shared/chrome/ChromeUtilityCluster";
import { StaticTitle } from "@/components/shared/chrome/InlineTitle";
import { Button } from "@/components/ui/button";
import {
  examTotals,
  type TaskAnswer,
  type TaskGrade,
} from "@/lib/grading/grading";
import { examModePath, examModeSlug, letterLabel } from "@/lib/exam/exam-helpers";

type ResultView = "overview" | "learningGoals" | "details" | "allTasks";

const VIEWS: ResultsViewItem[] = [
  { key: "overview", label: "Overview", icon: BarChart3 },
  { key: "learningGoals", label: "Learning Goals", icon: GraduationCap },
  { key: "details", label: "Details", icon: Table2 },
  { key: "allTasks", label: "All tasks", icon: ListChecks },
];

const ExamResults = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { data: exam, isLoading: examLoading } = useExam(id);
  const { data: tasks, isLoading: tasksLoading } = useTasks(id!);
  const { data: sections, isLoading: sectionsLoading } = useSections(id!);
  const { data: blocks } = useSectionBlocks(id!);
  const { data: answers } = useTaskAnswers(id!);
  const { data: grades } = useTaskGrades(id!);

  const [view, setView] = useState<ResultView>("overview");

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

  const labelById = useMemo(() => {
    const m = new Map<string, string>();
    const sortedSections = (sections ?? []).slice().sort((a, b) => a.position - b.position);
    const taskList = tasks ?? [];
    sortedSections.forEach((sec, secIdx) => {
      const sectionTasks = taskList
        .filter((tk) => (tk.section_id ?? null) === sec.id)
        .sort((a, b) => a.position - b.position);
      sectionTasks.forEach((tk, i) => m.set(tk.id, `${secIdx + 1}${letterLabel(i)})`));
    });
    const orphans = taskList
      .filter((tk) => (tk.section_id ?? null) === null)
      .sort((a, b) => a.position - b.position);
    orphans.forEach((tk, i) => m.set(tk.id, `${letterLabel(i)})`));
    return m;
  }, [tasks, sections]);

  if (examLoading || tasksLoading || sectionsLoading) return <EditorLoadingView />;
  if (!exam) return null;
  // Results are only valid for a finished exam; other statuses redirect to their
  // canonical mode rather than showing (potentially incomplete) final results.
  if (examModeSlug(exam.status) !== "results") {
    return <Navigate to={examModePath(exam.id, exam.status)} replace />;
  }

  const pct = totals.max > 0 ? (totals.earned / totals.max) * 100 : 0;

  // Deliberately re-open a finished exam for grading. This is the one sanctioned
  // path that moves status finished → grading; visiting /grade by URL never does.
  // Seeding the cache from the patch response lets GradeRoute render immediately
  // (status already `grading`) with no redirect bounce or edit-vs-status race.
  const reopenForGrading = async () => {
    const updated = await patchExam(exam.id, { status: "grading" });
    qc.setQueryData(examKey(exam.id), updated);
    navigate(`/exams/${exam.id}/grade`);
  };

  return (
    <div className="flex h-screen w-full flex-col overflow-hidden">
      <div className="flex min-h-0 flex-1">
        <ResultsSidebar
          views={VIEWS}
          currentView={view}
          onSelectView={(k) => setView(k as ResultView)}
          title={<StaticTitle value={exam.title} />}
          footer={
            <Button
              type="button"
              size="sm"
              variant="outline"
              className="w-full gap-1"
              onClick={reopenForGrading}
            >
              <PencilLine size={14} />
              Back to Grading
            </Button>
          }
        />

        <div className="flex min-w-0 flex-1 flex-col">
          {/* Mode banner (mirrors grading's "Grading Mode" bar, green tint). */}
          <div className="w-full border-b border-hestia-success/30 bg-hestia-success/10 text-hestia-success">
            <div className="flex w-full items-center gap-hestia-3 px-hestia-6 py-hestia-2">
              <CheckCircle2 size={16} className="shrink-0" />
              <span className="hestia-eyebrow">Final Results</span>
            </div>
          </div>

          <div className="flex min-h-0 flex-1">
            {view === "allTasks" ? (
              <AllTasksList
                tasks={tasks ?? []}
                sections={sections ?? []}
                blocks={blocks ?? []}
                answersById={answersById}
                gradesById={gradesById}
              />
            ) : (
              <main className="relative flex min-w-0 flex-1 flex-col">
                <div className="flex-1 overflow-y-auto">
                  <div className="mx-auto w-full max-w-[900px] space-y-hestia-5 px-hestia-5 py-hestia-8">
                    {view === "overview" && (
                      <ResilienceOverview
                        earned={totals.earned}
                        max={totals.max}
                        tasks={tasks ?? []}
                        grades={gradesById}
                        answers={answersById}
                        exam={exam}
                        onNavigate={(v) => setView(v)}
                      />
                    )}

                    {view === "learningGoals" && (
                      <LearningGoalsCard
                        tasks={tasks ?? []}
                        grades={gradesById}
                        answers={answersById}
                        examId={exam.id}
                      />
                    )}

                    {view === "details" && (
                      <>
                        <ByQuestionTypeCard
                          tasks={tasks ?? []}
                          grades={gradesById}
                          answers={answersById}
                        />

                        <TaskScoreBarChart
                          tasks={tasks ?? []}
                          grades={gradesById}
                          answers={answersById}
                          labelById={labelById}
                        />

                        <FiguresComparisonCard
                          tasks={tasks ?? []}
                          blocks={blocks ?? []}
                          grades={gradesById}
                          answers={answersById}
                        />

                        <TaskBreakdownTable
                          tasks={tasks ?? []}
                          grades={gradesById}
                          answers={answersById}
                          labelById={labelById}
                        />
                      </>
                    )}
                  </div>
                </div>
              </main>
            )}
          </div>
        </div>
      </div>

      <ChromeFooter
        left={
          <div className="flex items-center gap-hestia-3">
            <span className="text-xs tabular-nums text-hestia-text-muted">
              Overall Score
            </span>
            <span className="text-xs font-semibold tabular-nums text-hestia-text">
              {totals.earned}
              <span className="text-hestia-text-muted"> / {totals.max}</span>
            </span>
            <ScoreBar pct={pct} className="hidden w-32 sm:block" />
          </div>
        }
        right={<ChromeUtilityCluster />}
      />
    </div>
  );
};

export default ExamResults;
