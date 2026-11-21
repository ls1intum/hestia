import type { TaskGoalDto } from "@/lib/exam/exam-helpers";
import type { TaskGoalDisplay } from "@/components/shared/exam-content/read-only/ReadOnlyQuestionBlock";

/** A goal of a task as the grading view shows it, carrying the review marking of its link. */
export function toGoalDisplay(goal: TaskGoalDto): TaskGoalDisplay {
  return {
    id: goal.id,
    text: goal.text,
    bloomLevel: goal.bloom_level,
    soloLevel: goal.solo_level,
    review: goal.review,
    reviewLinkId: goal.review_link_id,
    courseId: goal.course_id,
    goalVersion: goal.goal_version,
    removed: goal.removed,
  };
}
