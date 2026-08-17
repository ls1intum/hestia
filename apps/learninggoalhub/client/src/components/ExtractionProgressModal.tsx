import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ExtractionStatus, LearningGoal } from "../api/client.ts";
import { api } from "../api/client.ts";
import { EXTRACTION_PHASES } from "../lib/extraction.ts";
import { fetchAllGoals } from "../lib/fetchGoals.ts";
import { buildCompetencyForest, COMPETENCY_ROLE_META } from "../lib/goals.ts";
import { useTheme } from "../theme/context.ts";
import iconLight from "../assets/logos/icon-light.svg";
import iconDark from "../assets/logos/icon-dark.svg";
import Button from "./Button.tsx";

type Props = {
  open: boolean;
  /** Latest polled snapshot for the run. */
  status?: ExtractionStatus;
  /** Set when the run failed. */
  error?: string | null;
  courseId?: number | null;
  /**
   * Opens straight into the skill review, ignoring run status entirely. The review reads the
   * course's goals from its own query, so it stays reachable long after the run — and after a
   * restart, when the in-memory progress tracker no longer remembers the run at all.
   */
  reviewOnly?: boolean;
  onClose: () => void;
};

type SkillSuggestion = {
  text: string;
  shortLabel?: string | null;
};

/**
 * "Analyzing course materials" overlay driven by the live status snapshot. Once the run succeeds it
 * switches to the existing summary and skill-review flow.
 */
export default function ExtractionProgressModal({
  open,
  status,
  error,
  courseId,
  reviewOnly = false,
  onClose,
}: Props) {
  const { resolved } = useTheme();
  const flame = resolved === "dark" ? iconDark : iconLight;
  const queryClient = useQueryClient();
  const [adding, setAdding] = useState(false);
  const [newSkill, setNewSkill] = useState("");
  const [suggestions, setSuggestions] = useState<SkillSuggestion[]>([]);
  const [suggestionErrors, setSuggestionErrors] = useState<Record<string, string>>({});

  const done = reviewOnly || status?.status === "SUCCEEDED";
  // The review is a one-way, deliberate step: no backdrop click, no Escape, no ✕. "Done" is the
  // only exit, and it is what records the review as taken care of.
  const reviewLocked = done;
  const goalsQuery = useQuery({
    queryKey: ["goals", courseId],
    queryFn: () => fetchAllGoals(courseId as number),
    enabled: open && done && courseId != null,
  });

  const goals: LearningGoal[] = useMemo(
    () => goalsQuery.data ?? [],
    [goalsQuery.data],
  );
  const terminalSkills = useMemo(
    () => buildCompetencyForest(goals),
    [goals],
  );

  useEffect(() => {
    if (!open) {
      setSuggestions([]);
      setSuggestionErrors({});
    }
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

  const renameMutation = useMutation({
    mutationFn: async (vars: { goalId: number; text: string }) => {
      const { error: updateError } = await api.PATCH(
        "/api/courses/{courseId}/learning-goals/{goalId}",
        {
          params: { path: { courseId: courseId as number, goalId: vars.goalId } },
          body: { text: vars.text },
        },
      );
      if (updateError) throw new Error("Could not rename the skill.");
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["goals", courseId] }),
  });

  const deleteGoalMutation = useMutation({
    mutationFn: async (goalId: number) => {
      const { error: deleteError } = await api.DELETE(
        "/api/courses/{courseId}/learning-goals/{goalId}",
        { params: { path: { courseId: courseId as number, goalId } } },
      );
      if (deleteError) throw new Error("Could not delete the skill.");
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
    },
  });

  const addSkillMutation = useMutation({
    mutationFn: async (text: string) => {
      const result = await api.POST(
        "/api/courses/{courseId}/learning-goals/terminal",
        { params: { path: { courseId: courseId as number } }, body: { text } },
      );
      if (!result.data) {
        throw new Error(
          result.response.status === 409
            ? "A skill with that wording already exists."
            : "Could not add the skill.",
        );
      }
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
    },
  });

  const suggestSkillsMutation = useMutation({
    mutationFn: async (): Promise<SkillSuggestion[]> => {
      const result = await api.POST(
        "/api/courses/{courseId}/learning-goals/skill-suggestions",
        { params: { path: { courseId: courseId as number } } },
      );
      if (!result.data) throw new Error("Could not fetch AI skill suggestions.");
      return result.data
        .filter((item) => typeof item.text === "string" && item.text.trim() !== "")
        .map((item) => ({ text: item.text as string, shortLabel: item.shortLabel }));
    },
    onSuccess: (newSuggestions) => {
      setSuggestions(newSuggestions);
      setSuggestionErrors({});
    },
  });

  const generateSkillMutation = useMutation({
    mutationFn: async (candidate: SkillSuggestion) => {
      const result = await api.POST(
        "/api/courses/{courseId}/learning-goals/terminal/generated",
        {
          params: { path: { courseId: courseId as number } },
          body: { text: candidate.text, shortLabel: candidate.shortLabel ?? undefined },
        },
      );
      if (!result.data) {
        throw new Error(
          result.response.status === 409
            ? "This skill already exists."
            : "Could not add the AI skill.",
        );
      }
    },
    onSuccess: async (_createdGoal, candidate) => {
      setSuggestions((current) => current.filter((item) => item.text !== candidate.text));
      setSuggestionErrors((current) => {
        const next = { ...current };
        delete next[candidate.text];
        return next;
      });
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
    },
    onError: (mutationError, candidate) => {
      setSuggestionErrors((current) => ({
        ...current,
        [candidate.text]: mutationError.message,
      }));
    },
  });

  if (!open) return null;

  const failed = error != null || status?.status === "FAILED";
  const running = !done && !failed;

  const total = status?.total ?? 0;
  const completed = status?.completed ?? 0;
  const percent = status?.percent ?? 0;
  // Sessions the run dropped. Zero when reopened via reviewOnly: the tracker is in-memory, so the
  // durable record of a thinned-out run is the extraction_run audit row, not this screen.
  const failedSessions = status?.failedSessions ?? 0;
  // Index of the phase the backend currently reports; -1 until the first poll lands ("Starting…").
  const activeIndex = status?.phase
    ? EXTRACTION_PHASES.findIndex((p) => p.key === status.phase)
    : -1;

  return (
    <div
      onClick={reviewLocked ? undefined : onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="extraction-progress-title"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="max-h-[calc(100vh-2rem)] w-full max-w-lg overflow-y-auto rounded-xl border border-hestia-border bg-hestia-surface p-6 shadow-lg sm:p-8"
      >
        <div className="flex items-center gap-3">
          <img
            src={flame}
            alt=""
            className={`h-9 w-9 ${running ? "animate-pulse" : ""}`}
          />
          <h2 id="extraction-progress-title" className="flex-1 text-xl">
            {done
              ? "Review your skills"
              : failed
                ? "Analysis failed"
                : "Analyzing course materials"}
          </h2>
          {!reviewLocked && (
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={onClose}
              aria-label="Close"
              className="shrink-0"
            >
              <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.6" className="h-5 w-5">
                <path d="M5 5l10 10M15 5L5 15" strokeLinecap="round" />
              </svg>
            </Button>
          )}
        </div>

        {running && (
          <div className="mt-6">
            <p className="text-sm text-hestia-text-muted">
              This runs once per upload. You can review and adjust everything afterwards.
            </p>
            <div className="mt-4 h-1.5 w-full overflow-hidden rounded-full bg-hestia-primary-muted">
              {total > 0 ? (
                <div
                  className="h-full rounded-full bg-hestia-primary transition-[width] duration-500 ease-out"
                  style={{ width: `${percent}%` }}
                />
              ) : (
                <div className="h-full w-1/3 animate-pulse rounded-full bg-hestia-primary" />
              )}
            </div>
            <ol className="mt-6 flex flex-col gap-3">
              {EXTRACTION_PHASES.map((phase, i) => {
                const state =
                  activeIndex < 0
                    ? i === 0
                      ? "active"
                      : "pending"
                    : i < activeIndex
                      ? "done"
                      : i === activeIndex
                        ? "active"
                        : "pending";
                return (
                  <li key={phase.key} className="flex items-center gap-3">
                    <PhaseTick state={state} index={i} />
                    <span
                      className={`text-sm ${
                        state === "active"
                          ? "font-medium text-hestia-text"
                          : state === "done"
                            ? "text-hestia-text-muted"
                            : "text-hestia-text-muted/60"
                      }`}
                    >
                      {phase.label}
                    </span>
                    {state === "active" && total > 0 && (
                      <span className="ml-auto tabular-nums text-xs text-hestia-text-muted">
                        {completed}/{total}
                      </span>
                    )}
                  </li>
                );
              })}
            </ol>
          </div>
        )}

        {done && (
          <>
            <p className="mt-6 text-sm text-hestia-text-muted">
              Take a quick look at the terminal skills we extracted — rename or remove any that
              are off.
            </p>
            {failedSessions > 0 && (
              <p className="mt-4 rounded-md border border-hestia-warning/40 bg-hestia-warning/10 px-3 py-2 text-sm text-hestia-text">
                <span aria-hidden="true">⚠ </span>
                {failedSessions === 1
                  ? "One session could not be analysed and contributed no skills."
                  : `${failedSessions} sessions could not be analysed and contributed no skills.`}{" "}
                Add anything that is missing below.
              </p>
            )}
            <div className="mt-4 max-h-72 overflow-y-auto rounded-lg border border-hestia-border bg-hestia-bg">
              {goalsQuery.isLoading && (
                <p className="px-4 py-6 text-center text-sm text-hestia-text-muted">
                  Loading skills…
                </p>
              )}
              {goalsQuery.isError && (
                <p className="px-4 py-6 text-center text-sm text-hestia-danger">
                  {(goalsQuery.error as Error).message}
                </p>
              )}
              {!goalsQuery.isLoading && !goalsQuery.isError && terminalSkills.length === 0 && (
                <p className="px-4 py-6 text-center text-sm text-hestia-text-muted">
                  No terminal skills were extracted for this course.
                </p>
              )}
              {terminalSkills.length > 0 && (
                <ul className="divide-y divide-hestia-border">
                  {terminalSkills.map((skill) => (
                    <SkillRow
                      key={skill.goal.id ?? skill.goal.text}
                      skill={skill}
                      renaming={renameMutation.isPending}
                      deleting={deleteGoalMutation.isPending}
                      onRename={(goalId, text) => renameMutation.mutate({ goalId, text })}
                      onDelete={() => {
                        if (skill.goal.id != null) deleteGoalMutation.mutate(skill.goal.id);
                      }}
                    />
                  ))}
                </ul>
              )}
            </div>
            <div className="mt-4 flex items-center gap-3">
              <Button
                variant="secondary"
                disabled={suggestSkillsMutation.isPending}
                onClick={() => suggestSkillsMutation.mutate()}
              >
                {suggestSkillsMutation.isPending ? "Finding suggestions…" : "Suggest skills with AI"}
              </Button>
              {suggestions.length > 0 && (
                <span className="text-xs text-hestia-text-muted">
                  Review each suggestion before adding it.
                </span>
              )}
            </div>
            {suggestSkillsMutation.isError && (
              <p className="mt-3 text-sm text-hestia-danger">
                {(suggestSkillsMutation.error as Error).message}
              </p>
            )}
            {suggestions.length > 0 && (
              <div className="mt-3 space-y-2">
                {suggestions.map((suggestion) => {
                  const accepting =
                    generateSkillMutation.isPending
                    && generateSkillMutation.variables?.text === suggestion.text;
                  return (
                    <div
                      key={suggestion.text}
                      className="rounded-lg border border-hestia-primary/40 bg-hestia-primary-muted/30 px-3 py-3"
                    >
                      <div className="flex items-start gap-3">
                        <div className="min-w-0 flex-1">
                          <p className="text-sm font-medium leading-relaxed text-hestia-text">
                            {suggestion.text}
                          </p>
                          {suggestion.shortLabel && (
                            <p className="mt-1 text-xs text-hestia-text-muted">
                              {suggestion.shortLabel}
                            </p>
                          )}
                        </div>
                        <div className="flex shrink-0 items-center gap-1">
                          <Button
                            size="sm"
                            disabled={generateSkillMutation.isPending}
                            onClick={() => generateSkillMutation.mutate(suggestion)}
                          >
                            {accepting ? (
                              <span className="flex items-center gap-1.5">
                                <span
                                  aria-hidden="true"
                                  className="h-3 w-3 animate-spin rounded-full border-2 border-current/40 border-t-current"
                                />
                                Adding…
                              </span>
                            ) : (
                              "Accept"
                            )}
                          </Button>
                          <Button
                            variant="neutral"
                            size="sm"
                            disabled={accepting}
                            onClick={() => {
                              setSuggestions((current) =>
                                current.filter((item) => item.text !== suggestion.text),
                              );
                              setSuggestionErrors((current) => {
                                const next = { ...current };
                                delete next[suggestion.text];
                                return next;
                              });
                            }}
                          >
                            Dismiss
                          </Button>
                        </div>
                      </div>
                      {suggestionErrors[suggestion.text] && (
                        <p className="mt-2 text-xs text-hestia-danger">
                          {suggestionErrors[suggestion.text]}
                        </p>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
            {adding ? (
              <form
                onSubmit={(event) => {
                  event.preventDefault();
                  const trimmed = newSkill.trim();
                  if (trimmed === "" || addSkillMutation.isPending) return;
                  addSkillMutation.mutate(trimmed, {
                    onSuccess: () => setNewSkill(""),
                  });
                }}
                className="mt-3 flex items-center gap-2"
              >
                <input
                  value={newSkill}
                  onChange={(event) => setNewSkill(event.target.value)}
                  autoFocus
                  disabled={addSkillMutation.isPending}
                  placeholder="Describe a skill students should master…"
                  className="min-w-0 flex-1 rounded-sm border-[1.5px] border-hestia-border bg-hestia-surface px-2.5 py-1.5 text-sm text-hestia-text transition focus:border-hestia-primary focus:outline-none"
                />
                <Button
                  variant="neutral"
                  onClick={() => {
                    setAdding(false);
                    setNewSkill("");
                    addSkillMutation.reset();
                  }}
                  disabled={addSkillMutation.isPending}
                >
                  Cancel
                </Button>
                <Button
                  type="submit"
                  disabled={newSkill.trim() === "" || addSkillMutation.isPending}
                >
                  {addSkillMutation.isPending ? (
                    <span className="flex items-center gap-1.5">
                      <span
                        aria-hidden="true"
                        className="h-3 w-3 animate-spin rounded-full border-2 border-current/40 border-t-current"
                      />
                      Adding…
                    </span>
                  ) : (
                    "Add"
                  )}
                </Button>
              </form>
            ) : (
              <Button
                variant="ghost"
                onClick={() => setAdding(true)}
                className="mt-3 text-hestia-primary hover:text-hestia-primary-hover"
              >
                <span aria-hidden="true" className="text-base leading-none">
                  +
                </span>
                Add a skill
              </Button>
            )}
            {addSkillMutation.isError && (
              <p className="mt-3 text-sm text-hestia-danger">
                {(addSkillMutation.error as Error).message}
              </p>
            )}
            {addSkillMutation.isPending && (
              <p className="mt-3 text-sm text-hestia-text-muted" aria-live="polite">
                Generating the skill’s sub-skills and knowledge…
              </p>
            )}
            {renameMutation.isError && (
              <p className="mt-3 text-sm text-hestia-danger">
                {(renameMutation.error as Error).message}
              </p>
            )}
            {deleteGoalMutation.isError && (
              <p className="mt-3 text-sm text-hestia-danger">
                {(deleteGoalMutation.error as Error).message}
              </p>
            )}
            <div className="mt-6 flex justify-end border-t border-hestia-border pt-4">
              <Button size="lg" onClick={onClose}>
                Done
              </Button>
            </div>
          </>
        )}

        {failed && (
          <>
            <p className="mt-6 rounded-md border border-hestia-danger/40 bg-hestia-danger/10 px-3 py-2 text-sm text-hestia-danger">
              {error ?? "Extraction failed."}
            </p>
            <div className="mt-6 flex justify-end">
              <Button variant="ghost" size="lg" onClick={onClose}>
                Close
              </Button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function SkillRow({
  skill,
  renaming,
  deleting,
  onRename,
  onDelete,
}: {
  skill: ReturnType<typeof buildCompetencyForest>[number];
  renaming: boolean;
  deleting: boolean;
  onRename: (goalId: number, text: string) => void;
  onDelete: () => void;
}) {
  const current = skill.goal.text ?? "";
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(current);
  useEffect(() => {
    if (editing) setDraft(current);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset the draft only when editing starts
  }, [editing]);

  const trimmed = draft.trim();
  const canSave = trimmed !== "" && trimmed !== current && !renaming;

  if (editing) {
    return (
      <li className="px-4 py-3">
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (canSave && skill.goal.id != null) {
              onRename(skill.goal.id, trimmed);
              setEditing(false);
            }
          }}
          className="flex items-center gap-2"
        >
          <input
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            autoFocus
            className="min-w-0 flex-1 rounded-sm border-[1.5px] border-hestia-border bg-hestia-surface px-2.5 py-1.5 text-sm text-hestia-text transition focus:border-hestia-primary focus:outline-none"
          />
          <Button variant="neutral" onClick={() => setEditing(false)}>
            Cancel
          </Button>
          <Button type="submit" disabled={!canSave}>
            Save
          </Button>
        </form>
      </li>
    );
  }

  return (
    <li className="group flex items-center gap-3 px-4 py-3">
      <span
        aria-hidden="true"
        className="h-2.5 w-2.5 shrink-0 rounded-full"
        style={{ backgroundColor: COMPETENCY_ROLE_META[skill.role].color }}
      />
      <span className="min-w-0 flex-1 text-sm leading-relaxed text-hestia-text">
        {current}
      </span>
      <div className="flex shrink-0 items-center gap-1">
        <Button
          variant="ghost"
          size="icon-sm"
          title="Rename skill"
          aria-label="Rename skill"
          onClick={() => setEditing(true)}
          className="opacity-0 focus-visible:opacity-100 group-hover:opacity-100"
        >
          <svg
            viewBox="0 0 20 20"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="h-4 w-4"
          >
            <path d="M13.5 3.5l3 3L7 16l-3.7.7L4 13z" />
          </svg>
        </Button>
        <Button
          variant="ghost"
          size="icon-sm"
          title="Delete skill"
          aria-label="Delete skill"
          disabled={deleting}
          onClick={onDelete}
          className="opacity-0 focus-visible:opacity-100 group-hover:opacity-100 hover:bg-hestia-danger hover:text-hestia-on-danger"
        >
          <svg
            viewBox="0 0 20 20"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="h-4 w-4"
          >
            <path d="M4 6h12M8 6V4h4v2M6 6l1 10h6l1-10" />
          </svg>
        </Button>
      </div>
    </li>
  );
}

function PhaseTick({
  state,
  index,
}: {
  state: "done" | "active" | "pending";
  index: number;
}) {
  if (state === "done") {
    return (
      <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-hestia-primary text-hestia-on-primary">
        <svg viewBox="0 0 20 20" fill="currentColor" className="h-3 w-3">
          <path
            fillRule="evenodd"
            d="M16.7 5.3a1 1 0 010 1.4l-7.5 7.5a1 1 0 01-1.4 0l-3.5-3.5a1 1 0 011.4-1.4l2.8 2.8 6.8-6.8a1 1 0 011.4 0z"
            clipRule="evenodd"
          />
        </svg>
      </span>
    );
  }
  if (state === "active") {
    return (
      <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-[1.5px] border-hestia-primary text-xs font-semibold tabular-nums text-hestia-primary ring-2 ring-hestia-primary-muted">
        {index + 1}
      </span>
    );
  }
  return (
    <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-[1.5px] border-hestia-border text-xs font-semibold tabular-nums text-hestia-text-muted/60">
      {index + 1}
    </span>
  );
}

