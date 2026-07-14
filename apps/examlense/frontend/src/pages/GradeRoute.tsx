import { useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useExam } from "@/hooks/use-exam";
import { examModePath, examModeSlug } from "@/lib/exam-helpers";
import { GradingView } from "@/pages/GradingView";
import { EditorLoadingView } from "@/components/exam-edit/EditorLoadingView";

const GradeRoute = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: exam, isLoading } = useExam(id);

  // Grading is only valid while the exam's status is `grading`. Any other status
  // redirects to that status's canonical mode — visiting /grade never moves the
  // exam into grading (a finished exam is re-opened only via an explicit action).
  useEffect(() => {
    if (!isLoading && exam && examModeSlug(exam.status) !== "grade") {
      navigate(examModePath(exam.id, exam.status), { replace: true });
    }
  }, [exam, isLoading, navigate]);

  if (isLoading) return <EditorLoadingView />;
  if (!exam) return null;
  if (examModeSlug(exam.status) !== "grade") return null;

  return <GradingView examId={exam.id} />;
};

export default GradeRoute;