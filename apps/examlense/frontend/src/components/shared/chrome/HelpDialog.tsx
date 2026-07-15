import { Info } from "lucide-react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import {
  StepGuide,
  EDIT_STEPS,
  GRADING_STEPS,
} from "./IntroStepGuide";

interface Props {
  variant: "edit" | "grading";
}

const CONFIG = {
  edit: {
    button: "Help",
    title: "How the editor works",
    body: "Structure the exam, add tasks and context, set points, and confirm every section.",
    steps: EDIT_STEPS,
  },
  grading: {
    button: "Help",
    title: "How grading works",
    body: "Review each AI answer, adjust scores, complete sections, and finish grading.",
    steps: GRADING_STEPS,
  },
} as const;

/** Footer info button that reopens the step-by-step help guide in a dialog. */
export const HelpDialog = ({ variant }: Props) => {
  const { button, title, body, steps } = CONFIG[variant];

  return (
    <Dialog>
      <Tooltip>
        <TooltipTrigger asChild>
          <DialogTrigger asChild>
            <button
              type="button"
              aria-label={button}
              className="inline-flex h-10 w-10 items-center justify-center rounded-hestia-md text-hestia-text-muted transition-colors hover:bg-hestia-primary-muted hover:text-hestia-primary"
            >
              <Info size={18} />
            </button>
          </DialogTrigger>
        </TooltipTrigger>
        <TooltipContent side="top">{button}</TooltipContent>
      </Tooltip>

      <DialogContent className="max-h-[88vh] overflow-y-auto sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{body}</DialogDescription>
        </DialogHeader>
        <StepGuide steps={steps} />
      </DialogContent>
    </Dialog>
  );
};
