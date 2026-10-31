import { useEffect, useRef, useState } from "react";
import type { ExtractionStatus } from "../api/client.ts";
import { useTheme } from "../theme/context.ts";
import iconLight from "../assets/logos/icon-light.svg";
import iconDark from "../assets/logos/icon-dark.svg";
import Button from "./Button.tsx";
import TopicSearchDialog from "./TopicSearchDialog.tsx";
import { useFocusTrap } from "../lib/useFocusTrap.ts";
import { RunFailure } from "./extractionProgress/RunFailure.tsx";
import { RunProgress } from "./extractionProgress/RunProgress.tsx";
import { SkillReview } from "./extractionProgress/SkillReview.tsx";

type Props = {
  open: boolean;
  /** Latest polled snapshot for the run. */
  status?: ExtractionStatus;
  /** Set when the run failed. */
  error?: string | null;
  /** Set while the status cannot be loaded; the last known progress stays on screen meanwhile. */
  statusUnavailable?: boolean;
  courseId?: number | null;
  /**
   * Opens straight into the skill review whatever the run status; `status` then only reports what
   * the run left out. The review reads the
   * course's goals from its own query, so it stays reachable long after the run — and after a
   * restart, when the in-memory progress tracker no longer remembers the run at all.
   */
  reviewOnly?: boolean;
  onClose: () => void;
};

/**
 * "Analyzing course materials" overlay driven by the live status snapshot. Once the run succeeds it
 * switches to the existing summary and skill-review flow.
 */
export default function ExtractionProgressModal({
  open,
  status,
  error,
  statusUnavailable = false,
  courseId,
  reviewOnly = false,
  onClose,
}: Props) {
  const { resolved } = useTheme();
  const flame = resolved === "dark" ? iconDark : iconLight;
  // The topics folded open in the review, which start folded shut so the review opens on the
  // course's outline, and the wording of a topic being added. Both are kept here because the
  // search for a typed topic in the slides, which adds and opens it, sits outside the review.
  const [expandedTopics, setExpandedTopics] = useState<Set<number>>(() => new Set());
  const [newTopic, setNewTopic] = useState("");
  // The typed topic being looked up in the slides, over the review.
  const [findingTopic, setFindingTopic] = useState<string | null>(null);

  const done = reviewOnly || status?.status === "SUCCEEDED";
  // The review is a one-way, deliberate step: no backdrop click, no Escape, no ✕. "Done" is the
  // only exit, and it is what records the review as taken care of.
  const reviewLocked = done;

  useEffect(() => {
    if (!open) setNewTopic("");
  }, [open]);

  // Escape closes while the run is in flight — the extraction is a background job, so dismissing
  // this view only stops watching it, it never abandons the run. The review step is exempt.
  useEffect(() => {
    if (!open || reviewLocked) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, reviewLocked, onClose]);

  const dialogRef = useRef<HTMLDivElement>(null);
  useFocusTrap(dialogRef, open);

  if (!open) return null;

  const failed = error != null || status?.status === "FAILED";
  const running = !done && !failed;

  const title = done
    ? "Review your skills"
    : failed
      ? "Analysis failed"
      : "Analyzing course materials";
  const subtitle = done
    ? "Accept, rename or dismiss each skill we extracted, grouped by topic."
    : failed
      ? null
      : "This runs once per upload. You can review and adjust everything afterwards.";

  const closeButton = (
    <Button variant="ghost" size="icon-sm" onClick={onClose} aria-label="Close">
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
    </Button>
  );

  return (
    <div
      ref={dialogRef}
      tabIndex={-1}
      className="fixed inset-0 z-50 focus:outline-none"
      role="dialog"
      aria-modal="true"
      aria-labelledby="extraction-progress-title"
    >
      {/* An opaque scrim rather than a backdrop-blur, matching the create-course and goal dialogs:
          blurring the page through this layer repaints all of it every frame. */}
      <div aria-hidden="true" className="absolute inset-0 bg-hestia-bg" />
      <div
        onClick={reviewLocked ? undefined : onClose}
        className="absolute inset-0 flex items-start justify-center overflow-y-auto p-4 sm:p-8"
      >
        {/* One animation for the whole panel rather than per card, as in the other two dialogs. */}
        <div
          onClick={(e) => e.stopPropagation()}
          className={`comp-unfold flex w-full flex-col gap-4 ${
            "max-w-2xl"
          }`}
        >
          {/* The header spans both columns, so the close button never rides on one of them. */}
          <div className="flex items-start justify-between gap-2">
            <div className="flex min-w-0 items-center gap-2.5">
              <img
                src={flame}
                alt=""
                className={`h-8 w-8 shrink-0 ${running ? "animate-pulse" : ""}`}
              />
              <div className="flex min-w-0 flex-col gap-1">
                <h2 id="extraction-progress-title" className="text-2xl">
                  {title}
                </h2>
                {subtitle && <p className="text-sm text-hestia-text-muted">{subtitle}</p>}
              </div>
            </div>
            {!reviewLocked && closeButton}
          </div>

          {running && <RunProgress status={status} statusUnavailable={statusUnavailable} />}

          {done && (
            <SkillReview
              courseId={courseId}
              status={status}
              expandedTopics={expandedTopics}
              setExpandedTopics={setExpandedTopics}
              newTopic={newTopic}
              setNewTopic={setNewTopic}
              onFindTopic={setFindingTopic}
              onDone={onClose}
            />
          )}

          {failed && (
            <RunFailure status={status} error={error} courseId={courseId} onClose={onClose} />
          )}
        </div>
      </div>
      {findingTopic != null && courseId != null && (
        <TopicSearchDialog
          courseId={courseId}
          topic={findingTopic}
          onClose={() => setFindingTopic(null)}
          onCreated={(topic) => {
            setFindingTopic(null);
            setNewTopic("");
            const topicId = topic.id;
            if (topicId != null) setExpandedTopics((current) => new Set(current).add(topicId));
          }}
        />
      )}
    </div>
  );
}
