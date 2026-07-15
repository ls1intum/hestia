import { useExam, useTasks } from "@/hooks/data/use-exam";
import { useSections, useSectionBlocks } from "@/hooks/data/use-sections";

/**
 * The four exam-scoped queries every route page opens together
 * (`ExamEdit`, `GradingView`, `ExamResults`). Returns the resolved data plus a
 * combined loading flag so pages don't re-declare the same fetch boilerplate.
 *
 * Deliberately excludes `useTaskAnswers`/`useTaskGrades` (only Grading + Results
 * need them, and they build their own lookup maps) and `useSectionFigures`
 * (keyed by block id, not exam id).
 */
export function useExamBundle(id: string | undefined) {
  const exam = useExam(id);
  const tasks = useTasks(id);
  const sections = useSections(id);
  const blocks = useSectionBlocks(id);
  return {
    exam: exam.data,
    tasks: tasks.data,
    sections: sections.data,
    blocks: blocks.data,
    isLoading:
      exam.isLoading || tasks.isLoading || sections.isLoading || blocks.isLoading,
  };
}
