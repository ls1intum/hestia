import { useEffect, useMemo, useRef, useState } from "react";
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
  // The AI suggestions live in a side panel the instructor opens deliberately: asking the model is
  // a round trip, and the review reads as a finished list until they do.
  const [suggestOpen, setSuggestOpen] = useState(false);

  // The skill that was just added by hand or accepted from a suggestion. The list is alphabetical,
  // so a new skill lands anywhere in it — the row scrolls itself into view and stays lit for a beat
  // so the addition is visibly the same list, not a silent write.
  const [justAddedId, setJustAddedId] = useState<number | null>(null);
  const justAddedTimer = useRef<number | null>(null);
  const flashAdded = (goalId?: number) => {
    if (goalId == null) return;
    if (justAddedTimer.current != null) window.clearTimeout(justAddedTimer.current);
    setJustAddedId(goalId);
    justAddedTimer.current = window.setTimeout(() => setJustAddedId(null), 6000);
  };
  useEffect(() => () => {
    if (justAddedTimer.current != null) window.clearTimeout(justAddedTimer.current);
  }, []);

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
  const skills = useMemo(
    () => buildCompetencyForest(goals),
    [goals],
  );

  useEffect(() => {
    if (!open) {
      setSuggestions([]);
      setSuggestionErrors({});
      setJustAddedId(null);
      setSuggestOpen(false);
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

  // Accepting is the review's positive half: it flips the goal out of PENDING so the rest of the
  // app can tell a reviewed skill from an untouched one. Dismissing has no state of its own — a
  // skill the instructor does not want is deleted, exactly like a rejected AI suggestion.
  const approveMutation = useMutation({
    mutationFn: async (vars: { goalId: number; approved: boolean }) => {
      const { error: updateError } = await api.PATCH(
        "/api/courses/{courseId}/learning-goals/{goalId}",
        {
          params: { path: { courseId: courseId as number, goalId: vars.goalId } },
          body: { status: vars.approved ? "APPROVED" : "PENDING" },
        },
      );
      if (updateError) throw new Error("Could not update the skill.");
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
      return result.data;
    },
    onSuccess: async (created) => {
      // Adding is done: fold the field away rather than leaving an empty one sitting open, so the
      // two entry points are what remains on screen.
      setAdding(false);
      setNewSkill("");
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
      flashAdded(created.id);
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
      return result.data;
    },
    onSuccess: async (created, candidate) => {
      setSuggestions((current) => current.filter((item) => item.text !== candidate.text));
      setSuggestionErrors((current) => {
        const next = { ...current };
        delete next[candidate.text];
        return next;
      });
      await queryClient.invalidateQueries({ queryKey: ["goals", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["course", courseId] });
      await queryClient.invalidateQueries({ queryKey: ["courses"] });
      flashAdded(created.id);
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

  const accepted = skills.filter((skill) => skill.goal.status === "APPROVED").length;

  const title = done
    ? "Review your skills"
    : failed
      ? "Analysis failed"
      : "Analyzing course materials";
  const subtitle = done
    ? "Accept, dismiss or rename each skill we extracted."
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
      className="fixed inset-0 z-50"
      role="dialog"
      aria-modal="true"
      aria-labelledby="extraction-progress-title"
    >
      {/* An opaque scrim rather than a backdrop-blur, matching the create-course and goal dialogs:
          blurring the page through this layer repaints all of it every frame. */}
      <div aria-hidden="true" className="absolute inset-0 bg-hestia-bg/90" />
      <div
        onClick={reviewLocked ? undefined : onClose}
        className="absolute inset-0 flex items-start justify-center overflow-y-auto p-4 sm:p-8"
      >
        {/* One animation for the whole panel rather than per card, as in the other two dialogs. */}
        <div
          onClick={(e) => e.stopPropagation()}
          className={`comp-unfold flex w-full flex-col gap-3.5 sm:mt-[6vh] ${
            done && suggestOpen ? "max-w-5xl" : "max-w-2xl"
          }`}
        >
          {/* The header spans both columns, so the close button never rides on one of them. */}
          <div className="flex items-start justify-between gap-2">
            <div className="flex min-w-0 items-center gap-2.5">
              <img
                src={flame}
                alt=""
                className={`h-6 w-6 shrink-0 ${running ? "animate-pulse" : ""}`}
              />
              <div className="flex min-w-0 flex-col gap-1">
                <span
                  id="extraction-progress-title"
                  className="text-xs font-semibold uppercase tracking-wider text-hestia-text"
                >
                  {title}
                </span>
                {subtitle && <p className="text-xs text-hestia-text-muted">{subtitle}</p>}
              </div>
            </div>
            {!reviewLocked && closeButton}
          </div>

          {running && (
            <div className="flex flex-col gap-4 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
              <div className="h-1.5 w-full overflow-hidden rounded-full bg-hestia-primary-muted">
                {total > 0 ? (
                  <div
                    className="h-full rounded-full bg-hestia-primary transition-[width] duration-500 ease-out"
                    style={{ width: `${percent}%` }}
                  />
                ) : (
                  <div className="progress-sweep h-full rounded-full bg-hestia-primary" />
                )}
              </div>
              <ol className="flex flex-col gap-3">
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

          {/* The course's own skills on the left, what the AI proposes adding to them on the right —
              the same "what it is / what goes into it" split the create-course dialog uses. The
              suggestions column can grow long without pushing the review actions out of view. */}
          {done && (
            <div className="flex w-full flex-col gap-3.5 lg:flex-row lg:items-stretch">
              <div className="flex w-full min-w-0 flex-1 flex-col gap-3.5">
                {failedSessions > 0 && (
                  <p className="rounded-lg border border-hestia-warning/40 bg-hestia-warning/10 px-4 py-3 text-sm text-hestia-text shadow-lg">
                    <span aria-hidden="true">⚠ </span>
                    {failedSessions === 1
                      ? "One session could not be analysed and contributed no skills."
                      : `${failedSessions} sessions could not be analysed and contributed no skills.`}{" "}
                    Add anything that is missing below.
                  </p>
                )}

                <div className="flex flex-col rounded-lg border border-hestia-border bg-hestia-surface shadow-lg">
                  <div className="flex items-center justify-between gap-3 border-b border-hestia-border px-4 py-3">
                    <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
                      Skills
                    </span>
                    {skills.length > 0 && (
                      <span className="tabular-nums text-xs text-hestia-text-muted">
                        {accepted} of {skills.length} accepted
                      </span>
                    )}
                  </div>
                  <div className="max-h-96 overflow-y-auto">
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
                    {!goalsQuery.isLoading && !goalsQuery.isError && skills.length === 0 && (
                      <p className="px-4 py-6 text-center text-sm text-hestia-text-muted">
                        No skills were extracted for this course.
                      </p>
                    )}
                    {skills.length > 0 && (
                      <ul className="divide-y divide-hestia-border">
                        {skills.map((skill) => (
                          <SkillRow
                            key={skill.goal.id ?? skill.goal.text}
                            skill={skill}
                            highlight={skill.goal.id != null && skill.goal.id === justAddedId}
                            renaming={renameMutation.isPending}
                            accepting={
                              approveMutation.isPending
                              && approveMutation.variables?.goalId === skill.goal.id
                            }
                            dismissing={
                              deleteGoalMutation.isPending
                              && deleteGoalMutation.variables === skill.goal.id
                            }
                            onRename={(goalId, text) => renameMutation.mutate({ goalId, text })}
                            onAccept={(approved) => {
                              if (skill.goal.id != null) {
                                approveMutation.mutate({ goalId: skill.goal.id, approved });
                              }
                            }}
                            onDismiss={() => {
                              if (skill.goal.id != null) deleteGoalMutation.mutate(skill.goal.id);
                            }}
                          />
                        ))}
                      </ul>
                    )}
                  </div>
                </div>

                {/* Everything that acts on the list sits in one bar: the two ways to add a skill on
                    the left, the exit on the right. */}
                <div className="flex flex-col gap-3 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
                  {adding && (
                    <form
                      onSubmit={(event) => {
                        event.preventDefault();
                        const trimmed = newSkill.trim();
                        if (trimmed === "" || addSkillMutation.isPending) return;
                        addSkillMutation.mutate(trimmed);
                      }}
                      className="flex items-center gap-2"
                    >
                      <input
                        value={newSkill}
                        onChange={(event) => setNewSkill(event.target.value)}
                        autoFocus
                        disabled={addSkillMutation.isPending}
                        placeholder="Describe a skill students should master…"
                        className="min-w-0 flex-1 rounded-sm border-[1.5px] border-hestia-border bg-hestia-bg px-2.5 py-1.5 text-sm text-hestia-text transition placeholder:text-hestia-text-muted focus:border-hestia-primary focus:outline-none"
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
                        Add
                      </Button>
                    </form>
                  )}
                  {addSkillMutation.isPending && (
                    <IndeterminateProgress label="Generating the skill’s sub-skills and knowledge…" />
                  )}
                  {addSkillMutation.isError && (
                    <p className="text-sm text-hestia-danger">
                      {(addSkillMutation.error as Error).message}
                    </p>
                  )}
                  {renameMutation.isError && (
                    <p className="text-sm text-hestia-danger">
                      {(renameMutation.error as Error).message}
                    </p>
                  )}
                  {approveMutation.isError && (
                    <p className="text-sm text-hestia-danger">
                      {(approveMutation.error as Error).message}
                    </p>
                  )}
                  {deleteGoalMutation.isError && (
                    <p className="text-sm text-hestia-danger">
                      {(deleteGoalMutation.error as Error).message}
                    </p>
                  )}
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div className="flex flex-wrap items-center gap-2">
                      {!adding && (
                        <Button
                          variant="neutral"
                          onClick={() => setAdding(true)}
                          className="text-hestia-primary"
                        >
                          <span aria-hidden="true" className="text-base leading-none">
                            +
                          </span>
                          Add a skill
                        </Button>
                      )}
                      <Button
                        variant="secondary"
                        aria-expanded={suggestOpen}
                        onClick={() => {
                          const opening = !suggestOpen;
                          setSuggestOpen(opening);
                          // Opening it is the request — this button is the only way to ask. It
                          // keeps suggestions nobody has acted on yet and asks again once the
                          // panel would otherwise open empty.
                          if (opening && suggestions.length === 0 && !suggestSkillsMutation.isPending) {
                            suggestSkillsMutation.mutate();
                          }
                        }}
                      >
                        Suggest new skills using AI
                      </Button>
                    </div>
                    <Button size="lg" onClick={onClose}>
                      Done
                    </Button>
                  </div>
                </div>
              </div>

              {suggestOpen && (
              /* Taken out of flow at `lg`, like the create-course dialog's file column: a long list
                 of suggestions then cannot outgrow the review beside it. The wrapper carries the
                 width and takes its height from the skills column; the list inside scrolls. */
              <div className="w-full min-w-0 lg:relative lg:w-96 lg:shrink-0">
              <div className="flex w-full flex-col gap-3 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg lg:absolute lg:inset-0 lg:min-h-0">
                <div className="flex items-start justify-between gap-2">
                  <div className="flex min-w-0 flex-col gap-1">
                    <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
                      AI suggestions
                    </span>
                    <p className="text-xs text-hestia-text-muted">
                      Broad skills the session goals already extracted from your materials point
                      to, but the list beside this does not cover yet. Accepting one adds it.
                    </p>
                  </div>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => setSuggestOpen(false)}
                    aria-label="Hide AI suggestions"
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
                  </Button>
                </div>
                {suggestSkillsMutation.isPending && (
                  <IndeterminateProgress label="Reading the course materials…" />
                )}
                {suggestSkillsMutation.isError && (
                  <p className="text-sm text-hestia-danger">
                    {(suggestSkillsMutation.error as Error).message}
                  </p>
                )}
                {!suggestSkillsMutation.isPending
                  && suggestSkillsMutation.isSuccess
                  && suggestions.length === 0 && (
                  <p className="text-xs text-hestia-text-muted">
                    Nothing left to suggest. Close this panel and open it again to ask once more.
                  </p>
                )}
                {suggestions.length > 0 && (
                  <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto">
                    {suggestions.map((suggestion) => {
                      const accepting =
                        generateSkillMutation.isPending
                        && generateSkillMutation.variables?.text === suggestion.text;
                      return (
                        <div
                          key={suggestion.text}
                          className="rounded-lg border border-hestia-primary/40 bg-hestia-primary-muted/30 px-3 py-3"
                        >
                          <p className="text-sm font-medium leading-relaxed text-hestia-text">
                            {suggestion.text}
                          </p>
                          {suggestion.shortLabel && (
                            <p className="mt-1 text-xs text-hestia-text-muted">
                              {suggestion.shortLabel}
                            </p>
                          )}
                          <div className="mt-2 flex items-center gap-1">
                            <Button
                              size="sm"
                              disabled={generateSkillMutation.isPending}
                              onClick={() => generateSkillMutation.mutate(suggestion)}
                            >
                              Accept
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
                          {accepting && (
                            <IndeterminateProgress label="Adding it to your skills…" />
                          )}
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
              </div>
              </div>
              )}
            </div>
          )}

          {failed && (
            <div className="flex flex-col gap-4 rounded-lg border border-hestia-border bg-hestia-surface p-4 shadow-lg">
              <p className="rounded-md border border-hestia-danger/40 bg-hestia-danger/10 px-3 py-2 text-sm text-hestia-danger">
                {error ?? "Extraction failed."}
              </p>
              <div className="flex justify-end">
                <Button variant="ghost" size="lg" onClick={onClose}>
                  Close
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * A server round trip with no percentage to report — adding a skill generates its whole sub-skill
 * and knowledge subtree — so the bar sweeps instead of pretending to fill.
 */
function IndeterminateProgress({ label }: { label: string }) {
  return (
    <div className="mt-2">
      <span className="text-xs text-hestia-text-muted" aria-live="polite">
        {label}
      </span>
      <div
        className="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-hestia-primary-muted"
        role="progressbar"
        aria-label={label}
      >
        <div className="progress-sweep h-full rounded-full bg-hestia-primary" />
      </div>
    </div>
  );
}

/**
 * One extracted skill. The three review actions sit side by side: rename fixes the wording, Accept
 * marks it reviewed, Dismiss deletes it — the same accept/dismiss pair the AI suggestions carry.
 */
function SkillRow({
  skill,
  highlight,
  renaming,
  accepting,
  dismissing,
  onRename,
  onAccept,
  onDismiss,
}: {
  skill: ReturnType<typeof buildCompetencyForest>[number];
  highlight: boolean;
  renaming: boolean;
  accepting: boolean;
  dismissing: boolean;
  onRename: (goalId: number, text: string) => void;
  onAccept: (approved: boolean) => void;
  onDismiss: () => void;
}) {
  const current = skill.goal.text ?? "";
  const approved = skill.goal.status === "APPROVED";
  // Extraction leaves this null, so a tag here means the skill did not come out of the materials.
  const provenance = skill.goal.creationProvenance;
  const provenanceLabel = provenance === "USER_CREATED"
    ? "Added by you"
    : provenance === "WIZARD_AI_SUBTREE"
      ? "AI added"
      : null;
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(current);
  const row = useRef<HTMLLIElement>(null);
  useEffect(() => {
    if (editing) setDraft(current);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reset the draft only when editing starts
  }, [editing]);

  // The list is alphabetical and scrolls, so a skill added at the bottom of the dialog can land
  // out of sight. Bring it to the reader rather than making them hunt for it.
  useEffect(() => {
    if (highlight) row.current?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }, [highlight]);

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
            className="min-w-0 flex-1 rounded-sm border-[1.5px] border-hestia-border bg-hestia-bg px-2.5 py-1.5 text-sm text-hestia-text transition focus:border-hestia-primary focus:outline-none"
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
    <li
      ref={row}
      className={`flex items-start gap-3 px-4 py-3 transition-colors duration-700 ${
        highlight
          ? "bg-hestia-primary-muted"
          : approved
            ? "bg-hestia-primary-muted/20"
            : ""
      }`}
    >
      <span
        aria-hidden="true"
        className="mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full"
        style={{ backgroundColor: COMPETENCY_ROLE_META[skill.role].color }}
      />
      <span className="min-w-0 flex-1 text-sm leading-relaxed text-hestia-text">
        {current}
        {provenanceLabel && (
          <span className="ml-2 whitespace-nowrap rounded-full border border-hestia-border px-2 py-0.5 text-[0.65rem] font-semibold uppercase tracking-wider text-hestia-text-muted">
            {provenanceLabel}
          </span>
        )}
        {highlight && (
          <span className="ml-2 whitespace-nowrap rounded-full bg-hestia-primary px-2 py-0.5 text-[0.65rem] font-semibold uppercase tracking-wider text-hestia-on-primary">
            Just added
          </span>
        )}
      </span>
      <div className="flex shrink-0 items-center gap-1">
        <Button
          variant="ghost"
          size="icon-sm"
          title="Rename skill"
          aria-label="Rename skill"
          onClick={() => setEditing(true)}
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
        {approved ? (
          <Button
            variant="ghost"
            size="sm"
            disabled={accepting}
            title="Accepted — click to undo"
            aria-label="Undo accept"
            onClick={() => onAccept(false)}
            className="text-hestia-primary"
          >
            <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true" className="h-3.5 w-3.5">
              <path
                fillRule="evenodd"
                d="M16.7 5.3a1 1 0 010 1.4l-7.5 7.5a1 1 0 01-1.4 0l-3.5-3.5a1 1 0 011.4-1.4l2.8 2.8 6.8-6.8a1 1 0 011.4 0z"
                clipRule="evenodd"
              />
            </svg>
            Accepted
          </Button>
        ) : (
          <Button size="sm" disabled={accepting} onClick={() => onAccept(true)}>
            {accepting ? "Accepting…" : "Accept"}
          </Button>
        )}
        <Button
          variant="neutral"
          size="sm"
          title="Remove this skill from the course"
          disabled={dismissing}
          onClick={onDismiss}
        >
          {dismissing ? "Dismissing…" : "Dismiss"}
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

