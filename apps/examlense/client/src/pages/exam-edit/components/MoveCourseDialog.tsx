import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { FolderInput } from "lucide-react";

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
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { moveExamToCourse } from "@/lib/api/api-client";
import { examKey, tasksKey } from "@/hooks/data/use-exam";
import { examLearningGoalsKey, useCourses } from "@/hooks/data/use-learning-goals";
import { useToast } from "@/hooks/ui/use-toast";
import { courseLabel } from "@/lib/learning-goals/learning-goals";

/** Text of the confirmation: moving drops the links to the old course's goals. */
export const MOVE_COURSE_CONFIRM_TEXT =
  "Moving the exam removes the links of its tasks to learning goals of the current course. " +
  "Links to goals of other courses stay.";

interface Props {
  examId: string;
  courseId: string;
}

/** "Move to course" action of the exam page, with a confirmation before anything changes. */
export const MoveCourseDialog = ({ examId, courseId }: Props) => {
  const { data: courses } = useCourses();
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const [target, setTarget] = useState<string>("");

  // Only courses the user may write to can take the exam.
  const targets = (courses ?? []).filter(
    (c) => c.id !== courseId && (c.role === "EDITOR" || c.role === "OWNER"),
  );
  const current = (courses ?? []).find((c) => c.id === courseId);

  const move = async () => {
    try {
      await moveExamToCourse(examId, target);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: examKey(examId) }),
        queryClient.invalidateQueries({ queryKey: tasksKey(examId) }),
        queryClient.invalidateQueries({ queryKey: examLearningGoalsKey(examId) }),
        queryClient.invalidateQueries({ queryKey: ["exams-list"] }),
      ]);
      toast({ title: "Exam moved to the course." });
    } catch {
      toast({ title: "Could not move the exam.", variant: "destructive" });
    }
    setTarget("");
  };

  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          aria-label="Move to course"
          title={current ? `Course: ${courseLabel(current)}` : "Move to course"}
        >
          <FolderInput size={14} />
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Move to course</AlertDialogTitle>
          <AlertDialogDescription>{MOVE_COURSE_CONFIRM_TEXT}</AlertDialogDescription>
        </AlertDialogHeader>
        <Select value={target} onValueChange={setTarget}>
          <SelectTrigger aria-label="Target course">
            <SelectValue placeholder="Choose a course" />
          </SelectTrigger>
          <SelectContent>
            {targets.map((c) => (
              <SelectItem key={c.id} value={c.id}>
                {courseLabel(c)}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <AlertDialogFooter>
          <AlertDialogCancel onClick={() => setTarget("")}>Cancel</AlertDialogCancel>
          <AlertDialogAction disabled={!target} onClick={move}>
            Move
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
};
