import { cn } from "@/lib/utils/utils";
import type { Task } from "@/lib/exam/exam-helpers";
import type { TaskAnswer } from "@/lib/grading/grading";

interface Props {
  task: Task;
  answer: TaskAnswer | undefined;
}

/**
 * Read-only choice options with the AI's picks and the correct answers marked:
 * picked → filled ring, correct → success outline. Shared by the grading and
 * results answer cards so the two can't drift apart.
 */
export const ReadOnlyOptionList = ({ task, answer }: Props) => {
  const correctIds = new Set(
    (task.options ?? []).flatMap((o) => (o.is_correct ? [o.id] : [])),
  );
  const pickedIds = new Set(answer?.selected_option_ids ?? []);

  return (
    <ul className="space-y-1.5">
      {(task.options ?? []).map((o) => {
        const isCorrect = correctIds.has(o.id);
        const isPicked = pickedIds.has(o.id);
        return (
          <li
            key={o.id}
            className={cn(
              "flex items-start gap-hestia-2 rounded-hestia-sm border border-hestia-border-subtle bg-hestia-surface/60 px-hestia-2 py-1.5 text-sm",
              isPicked && "border-hestia-primary/60 bg-hestia-primary-muted/20",
              isCorrect && "ring-1 ring-hestia-success/40",
            )}
          >
            <span
              aria-hidden
              className={cn(
                "mt-0.5 inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full border",
                isPicked
                  ? "border-hestia-primary bg-hestia-primary text-white"
                  : "border-hestia-border text-transparent",
              )}
            >
              {isPicked && (
                <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden />
              )}
            </span>
            <span className="min-w-0 flex-1 break-words text-hestia-text">
              {o.text || <span className="italic text-hestia-text-muted">—</span>}
            </span>
          </li>
        );
      })}
    </ul>
  );
};
