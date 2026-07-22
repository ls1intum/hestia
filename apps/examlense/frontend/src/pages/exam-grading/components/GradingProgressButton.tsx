import { ArrowDown, ArrowRight, CheckCircle2 } from "lucide-react";
import {
  ProgressCtaButton,
  type ProgressCtaTone,
} from "@/components/shared/chrome/ProgressCtaButton";
import { type Task } from "@/lib/exam/exam-helpers";

interface Props {
  /** Tasks of the currently visible section. */
  currentSectionTasks: Task[];
  /** True for tasks still missing a score (from `effectiveScore`). */
  pendingByTaskId: Map<string, boolean>;
  /** Lowercase letter labels (a, b, c…) keyed by task id, for "missing" hints. */
  taskLetterById: Map<string, string>;
  /** True when every task across the whole exam has been graded. */
  allGraded: boolean;
  /** Jump (scroll + focus) to the first ungraded task in the current section. */
  onJumpToTask: (taskId: string) => void;
  /** Advance to the next section that still has ungraded tasks. */
  onAdvanceSection: () => void;
  /** Open the "Finish grading" confirm dialog. */
  onFinish: () => void;
}

const firstPendingTaskId = (
  tasks: Task[],
  pendingByTaskId: Map<string, boolean>,
): string | null => {
  const sorted = tasks.slice().sort((a, b) => a.position - b.position);
  for (const t of sorted) {
    if (pendingByTaskId.get(t.id)) return t.id;
  }
  return null;
};

/**
 * Footer progress button for the grading view. Mirrors the editor's
 * SectionProgressButton but with grading semantics ("graded" = a score has been
 * entered). Morphs across three states:
 *   • in-progress → jumps to the first ungraded task
 *   • section fully graded → advances to the next ungraded section
 *   • whole exam graded → opens the Finish Grading dialog
 */
export const GradingProgressButton = ({
  currentSectionTasks,
  pendingByTaskId,
  taskLetterById,
  allGraded,
  onJumpToTask,
  onAdvanceSection,
  onFinish,
}: Props) => {
  const total = currentSectionTasks.length;
  const graded = currentSectionTasks.filter(
    (tk) => !pendingByTaskId.get(tk.id),
  ).length;
  const missingLabels = currentSectionTasks
    .slice()
    .sort((a, b) => a.position - b.position)
    .filter((tk) => pendingByTaskId.get(tk.id))
    .map((tk) => taskLetterById.get(tk.id) ?? "")
    .filter(Boolean);

  const isEmpty = total === 0;
  const sectionDone = !isEmpty && graded === total;
  const pct = total === 0 ? 0 : Math.round((graded / total) * 100);

  let label: string;
  let icon: React.ReactNode = null;
  let handleClick: () => void = () => {};
  let disabled = false;
  let tone: ProgressCtaTone = "progress";

  if (isEmpty) {
    label = "No tasks in this section";
    disabled = true;
  } else if (allGraded) {
    tone = "success";
    label = "Finish Grading";
    icon = <CheckCircle2 size={14} />;
    handleClick = onFinish;
  } else if (sectionDone) {
    tone = "success";
    label = "Go to next section";
    icon = <ArrowRight size={14} />;
    handleClick = onAdvanceSection;
  } else {
    const shown = missingLabels.slice(0, 3).join(", ");
    const labels = missingLabels.length > 3 ? `${shown}, …` : shown;
    label = `${graded}/${total} graded${labels ? ` · missing ${labels}` : ""}`;
    icon = (
      <ArrowDown
        size={14}
        className="animate-bounce transition-transform group-hover:translate-y-0.5"
      />
    );
    handleClick = () => {
      const id = firstPendingTaskId(currentSectionTasks, pendingByTaskId);
      if (id) onJumpToTask(id);
    };
  }

  const fillPct = tone === "success" ? 100 : pct;

  return (
    <ProgressCtaButton
      label={label}
      icon={icon}
      tone={tone}
      fillPct={fillPct}
      disabled={disabled}
      showPing={tone === "success"}
      onClick={handleClick}
    />
  );
};
