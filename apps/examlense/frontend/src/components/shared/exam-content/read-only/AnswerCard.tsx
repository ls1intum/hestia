import type { ReactNode } from "react";
import { cn } from "@/lib/utils/utils";
import { BlockCard } from "@/components/shared/exam-content/BlockCard";
import "./AnswerCard.css";

interface Props {
  /** Ungraded → the grading-violet ring pulses to draw attention (grading only). */
  pending?: boolean;
  className?: string;
  children: ReactNode;
}

/**
 * The violet-tinted, headerless "AI answer" card shared by grading and results.
 * Owning the tint (and the pending pulse ring) in one place keeps the two views
 * from drifting. The score control — editable in grading, read-only in results —
 * is supplied by the caller as part of `children`.
 */
export const AnswerCard = ({ pending, className, children }: Props) => (
  <BlockCard
    variant="primary"
    body={children}
    className={cn(
      "border-hestia-grading/25 bg-hestia-grading/10",
      pending && "pulse-grading-border",
      className,
    )}
  />
);
