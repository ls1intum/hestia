import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function parseStepDuration(stepString: string): number {
  const match = stepString.match(/^(\d+)\s*(?:min|m)(?:utes?)?\s*(?:—|-|–|:)?\s*(.*)/i);
  return match ? parseInt(match[1], 10) : 0;
}

export function getSessionStatus(session: any) {
  const isFinished = session.status === "complete" && session.currentStep === "finished";
  const isReadyForPrep = session.status === "complete" && session.currentStep !== "finished";
  const isDraft = session.status !== "complete";

  let statusLabel = "In progress";
  if (isFinished) statusLabel = "Completed";
  else if (isReadyForPrep) statusLabel = "Ready for preparation";

  let statusShort = "Draft";
  if (isFinished) statusShort = "Done";
  else if (isReadyForPrep) statusShort = "Ready";

  let statusClass = "bg-amber-500/15 text-amber-600 dark:text-amber-400";
  if (isFinished) statusClass = "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400";
  else if (isReadyForPrep) statusClass = "bg-blue-500/15 text-blue-600 dark:text-blue-400";

  let iconClass = "bg-amber-500/15 text-amber-500";
  if (isFinished) iconClass = "bg-emerald-500/15 text-emerald-500";
  else if (isReadyForPrep) iconClass = "bg-blue-500/15 text-blue-500";

  return { isFinished, isReadyForPrep, isDraft, statusLabel, statusShort, statusClass, iconClass };
}
