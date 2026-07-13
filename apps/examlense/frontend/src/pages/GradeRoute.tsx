import { useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { useExam } from "@/hooks/use-exam";
import { GradingView } from "@/pages/GradingView";
import { EditorLoadingView } from "@/components/exam-edit/EditorLoadingView";

const GradeRoute = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: exam, isLoading } = useExam(id);

  useEffect(() => {
    if (!isLoading && exam && exam.status !== "grading" && exam.status !== "finished") {
      navigate(`/exams/${id}/edit`, { replace: true });
    }
  }, [exam, isLoading, id, navigate]);

  if (isLoading) return <EditorLoadingView />;
  if (!exam) return null;
  if (exam.status !== "grading" && exam.status !== "finished") return null;

  return <GradingView examId={exam.id} />;
};

export default GradeRoute;