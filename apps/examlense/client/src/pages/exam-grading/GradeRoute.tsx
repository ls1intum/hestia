import { useParams } from "react-router-dom";
import { useExam } from "@/hooks/data/use-exam";
import { GradingView } from "@/pages/exam-grading/GradingView";
import { EditorLoadingView } from "@/components/shared/exam-content/EditorLoadingView";
import { examModeRedirect } from "@/components/shared/ExamModeRedirect";

const GradeRoute = () => {
  const { id } = useParams<{ id: string }>();
  const { data: exam, isLoading } = useExam(id);

  if (isLoading) return <EditorLoadingView />;
  if (!exam) return null;
  // Grading is only valid while the exam's status is `grading`. Any other status
  // redirects to that status's canonical mode — visiting /grade never moves the
  // exam into grading (a finished exam is re-opened only via an explicit action).
  const redirect = examModeRedirect(exam, "grade");
  if (redirect) return redirect;

  return <GradingView examId={exam.id} />;
};

export default GradeRoute;
