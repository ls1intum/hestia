import { useEffect } from "react";
import type { LearningGoal } from "../api/client.ts";
import { GoalDetailContent } from "./GoalDetailPanel.tsx";

/**
 * Detail overlay for the List view, where there is no persistent side panel. Opened by clicking a
 * goal row (or its relationship count); shows the goal's taxonomy, sources and exact relationships.
 */
export default function GoalDetailModal({
  goal,
  onClose,
}: {
  goal: LearningGoal | null;
  onClose: () => void;
}) {
  useEffect(() => {
    if (!goal) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [goal, onClose]);

  if (!goal) return null;

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/50 p-4 sm:p-8"
      role="dialog"
      aria-modal="true"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="relative w-full max-w-lg rounded-xl border border-hestia-border bg-hestia-surface p-6 shadow-xl"
      >
        <button
          onClick={onClose}
          aria-label="Close"
          className="absolute right-3 top-3 flex h-8 w-8 items-center justify-center rounded-md text-hestia-text-muted transition hover:bg-hestia-bg hover:text-hestia-text"
        >
          <svg
            viewBox="0 0 20 20"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            className="h-4 w-4"
          >
            <path d="M5 5l10 10M15 5L5 15" />
          </svg>
        </button>
        <div className="pr-6">
          <GoalDetailContent goal={goal} />
        </div>
      </div>
    </div>
  );
}
