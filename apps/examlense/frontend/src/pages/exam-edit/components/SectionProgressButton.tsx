import { AlertTriangle, ArrowDown, ArrowRight, Check } from "lucide-react";
import { taskMissingScore, type Task } from "@/lib/exam/exam-helpers";
import { ProgressCtaButton } from "@/components/shared/chrome/ProgressCtaButton";

interface Props {
  /** Tasks of the currently visible section. */
  currentSectionTasks: Task[];
  /** Lowercase letter labels (a, b, c…) keyed by task id, for "missing" hints. */
  taskLetterById: Map<string, string>;
  /** True when every section across the exam is ready. */
  allSectionsReady: boolean;
  /** True when the first-open visual guide (not a real section) is showing. */
  isIntro: boolean;
  /** Dismiss the visual guide and open the first section. */
  onStartReview: () => void;
  /** True when a block in the current section is missing content (empty prompt/context/figure). */
  hasMissingContent: boolean;
  /** Jump (scroll + expand) to the first content-missing block in the current section. */
  onJumpToMissingContent: () => void;
  /** Jump (scroll + expand) to the first task missing a score in the current section. */
  onJumpToTask: (taskId: string) => void;
  /** Confirm current section and advance to the next unconfirmed one. */
  onAdvanceSection: () => void;
  /** Open the existing "send to evaluation" AlertDialog. */
  onStartSolving: () => void;
}

const firstUnscoredTaskId = (tasks: Task[]): string | null => {
  const sorted = tasks.slice().sort((a, b) => a.position - b.position);
  for (const t of sorted) {
    if (taskMissingScore(t)) return t.id;
  }
  return null;
};

/**
 * Footer progress button used in the exam editor. Visualises score-completion
 * for the currently visible section as a left-to-right fill. Click target
 * morphs across three states:
 *   • in-progress → jumps to the first unscored task
 *   • section ready → confirms the section and advances to the next one
 *   • all sections ready → opens the Start Solving confirm dialog
 */
export const SectionProgressButton = ({
  currentSectionTasks,
  taskLetterById,
  allSectionsReady,
  isIntro,
  onStartReview,
  hasMissingContent,
  onJumpToMissingContent,
  onJumpToTask,
  onAdvanceSection,
  onStartSolving,
}: Props) => {
  const total = currentSectionTasks.length;
  const scored = currentSectionTasks.filter((tk) => !taskMissingScore(tk)).length;
  const missingLabels = currentSectionTasks
    .slice()
    .sort((a, b) => a.position - b.position)
    .filter((tk) => taskMissingScore(tk))
    .map((tk) => taskLetterById.get(tk.id) ?? "")
    .filter(Boolean);

  const isEmpty = total === 0;
  const sectionReady = !isEmpty && scored === total;
  const pct = total === 0 ? 0 : Math.round((scored / total) * 100);

  // Resolve label + click action by state.
  let label: string;
  let icon: React.ReactNode = null;
  let handleClick: () => void = () => {};
  let disabled = false;
  let tone: "progress" | "success" | "warning" = "progress";

  if (isIntro) {
    tone = "success";
    label = "Start reviewing the content";
    icon = <ArrowRight size={14} />;
    handleClick = onStartReview;
  } else if (isEmpty) {
    label = "Add at least one task to this section";
    disabled = true;
  } else if (hasMissingContent) {
    tone = "warning";
    label = "Content missing";
    icon = <AlertTriangle size={14} />;
    handleClick = onJumpToMissingContent;
  } else if (allSectionsReady) {
    tone = "success";
    label = "Start solving";
    icon = <Check size={14} />;
    handleClick = onStartSolving;
  } else if (sectionReady) {
    tone = "success";
    label = "Confirm & Go to next Section";
    icon = <ArrowRight size={14} />;
    handleClick = onAdvanceSection;
  } else {
    const shown = missingLabels.slice(0, 3).join(", ");
    const labels =
      missingLabels.length > 3 ? `${shown}, …` : shown;
    label = `${scored}/${total} scored · missing ${labels}`;
    icon = (
      <ArrowDown
        size={14}
        className="animate-bounce transition-transform group-hover:translate-y-0.5"
      />
    );
    handleClick = () => {
      const id = firstUnscoredTaskId(currentSectionTasks);
      if (id) onJumpToTask(id);
    };
  }

  const fillPct = tone === "success" || tone === "warning" ? 100 : pct;

  return (
    <ProgressCtaButton
      label={label}
      icon={icon}
      tone={tone}
      fillPct={fillPct}
      disabled={disabled}
      showPing={tone === "success" && !isIntro}
      onClick={handleClick}
    />
  );
};
