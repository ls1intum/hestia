import { solverModelLabel as resolveSolverModelLabel } from "@/lib/llm-models";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { ChromeFooter } from "./chrome/ChromeFooter";
import { ChromeUtilityCluster } from "./chrome/ChromeUtilityCluster";
import { SectionProgressButton } from "./SectionProgressButton";
import type { Exam, Task } from "@/lib/exam-helpers";

interface Props {
  exam: Exam;
  onSendToEvaluation: () => void;
  solverModelId: string | null;
  /** Tasks belonging to the currently visible section. */
  currentSectionTasks: Task[];
  /** Lowercase labels (a, b…) for tasks of the visible section. */
  taskLetterById: Map<string, string>;
  /** True when every real section in the exam is ready. */
  allSectionsReady: boolean;
  onJumpToTask: (taskId: string) => void;
  onAdvanceSection: () => void;
  /** Controlled open state of the "Send for evaluation" dialog. */
  startSolvingOpen: boolean;
  onStartSolvingOpenChange: (open: boolean) => void;
}

export const ExamEditFooter = ({
  exam,
  onSendToEvaluation,
  solverModelId,
  currentSectionTasks,
  taskLetterById,
  allSectionsReady,
  onJumpToTask,
  onAdvanceSection,
  startSolvingOpen,
  onStartSolvingOpenChange,
}: Props) => {
  const solverModelLabel =
    solverModelId == null ? "" : resolveSolverModelLabel(solverModelId);

  return (
    <>
      <ChromeFooter
        left={
          solverModelLabel ? (
            <span
              title="Model used to grade this exam"
              className="whitespace-nowrap text-[11px] text-hestia-text-muted"
            >
              {`Solver: ${solverModelLabel}`}
            </span>
          ) : null
        }
        center={
          <SectionProgressButton
            currentSectionTasks={currentSectionTasks}
            taskLetterById={taskLetterById}
            allSectionsReady={allSectionsReady}
            onJumpToTask={onJumpToTask}
            onAdvanceSection={onAdvanceSection}
            onStartSolving={() => onStartSolvingOpenChange(true)}
          />
        }
        right={
          <ChromeUtilityCluster helpVariant="edit" />
        }
      />

      <AlertDialog open={startSolvingOpen} onOpenChange={onStartSolvingOpenChange}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              Send this exam for evaluation?
            </AlertDialogTitle>
            <AlertDialogDescription>
              We'll start evaluating in the background. You can leave the page and come back at any time. You won't be able to edit this exam once you process it.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={(ev) => {
                ev.preventDefault();
                onStartSolvingOpenChange(false);
                onSendToEvaluation();
              }}
              className="bg-hestia-success text-white hover:bg-hestia-success/90"
            >
              Send for evaluation
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
};
