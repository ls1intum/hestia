import type { AlignmentReview } from "./workshop-generator";

export function reviewReasonLabel(reason: string | undefined | null): string {
  return reason === "GOAL_REMOVED" ? "Learning goal removed" : "Learning goal changed";
}

/** What the linked object is, by module and link type, when its module gives no name. */
export function reviewTargetKind(review: Pick<AlignmentReview, "module" | "targetType">): string {
  if (review.module === "TEACHING" || review.targetType === "SESSION") return "Session";
  if (review.module === "ASSESSMENT" || review.targetType === "TASK") return "Exam task";
  if (review.module === "MATERIALS" || review.targetType === "DOCUMENT") return "Document";
  return "Item";
}

/** The target's name as its module gave it, with the kind as prefix or as fallback. */
export function reviewTargetName(review: Pick<AlignmentReview, "module" | "targetType" | "targetLabel">): string {
  const kind = reviewTargetKind(review);
  const label = review.targetLabel?.trim();
  return label ? `${kind}: ${label}` : kind;
}
