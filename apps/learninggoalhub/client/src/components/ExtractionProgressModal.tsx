import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { ExtractionStatus, ExtractionSummary, LearningGoal } from "../api/client.ts";
import { api } from "../api/client.ts";
import { buildCompetencyForest, COMPETENCY_ROLE_META } from "../lib/goals.ts";
import { useTheme } from "../theme/context.ts";
import iconLight from "../assets/logos/icon-light.svg";
import iconDark from "../assets/logos/icon-dark.svg";

type Props = {
  open: boolean;
  /** Latest polled snapshot while the run is in flight. */
  status?: ExtractionStatus;
  /** Final summary once the POST resolves (authoritative over the polled snapshot). */
  result?: ExtractionSummary | null;
  /** Set when the run failed. */
  error?: string | null;
  courseId?: number | null;
  onClose: () => void;
};

type Phase = NonNullable<ExtractionStatus["phase"]>;

/** Ordered list of phases the backend walks through, with their display labels. */
const PHASES: { key: Phase; label: string }[] = [
  { key: "OUTLINING", label: "Outlining documents" },
  { key: "PARSING", label: "Parsing documents" },
  { key: "EXTRACTING", label: "Extracting learning goals" },
  { key: "CLASSIFYING", label: "Classifying (Bloom & SOLO)" },
  { key: "EMBEDDING", label: "Computing embeddings" },
  { key: "PERSISTING", label: "Saving learning goals" },
];

/**
 * Screen 3 — "Analyzing course materials" overlay shown while the (synchronous) extraction POST is
 * in flight. It polls {@code GET /extract/status} for the live phase + per-phase counts and renders
 * a real progress bar, then switches to the summary once the run resolves.
 */
export default function ExtractionProgressModal({
  open,
  status,
  result,
  error,
  courseId,
  onClose,
}: Props) {
  const { resolved } = useTheme();
  const flame = resolved === "dark" ? iconDark : iconLight;
  const queryClient = useQueryClient();
  const [step, setStep] = useState<1 | 2>(1);

  const done = result != null;
  const goalsQuery = useQuery({
    queryKey: ["goals", courseId],
    queryFn: async () => {
      const { data, error: queryError } = await api.GET(
        "/api/courses/{courseId}/learning-goals",
        { params: { path: { courseId: courseId as number }, query: { size: 500 } } },
      );
      if (queryError || !data) throw new Error("Could not load learning goals.");
      return data;
    },
    enabled: open && done && courseId != null,
  });

  const goals: LearningGoal[] = useMemo(
    () => goalsQuery.data?.content ?? [],
    [goalsQuery.data],
  );
  const terminalSkills = useMemo(
    () => buildCompetencyForest(goals),
    [goals],
  );

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

  if (!open) return null;

  const failed = error != null;
  const running = !done && !failed;

  const total = status?.total ?? 0;
  const completed = status?.completed ?? 0;
  const percent = total > 0 ? Math.min(100, Math.round((completed / total) * 100)) : 0;
  // Index of the phase the backend currently reports; -1 until the first poll lands ("Starting…").
  const activeIndex = status?.phase ? PHASES.findIndex((p) => p.key === status.phase) : -1;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="extraction-progress-title"
    >
      <div className="w-full max-w-lg rounded-xl border border-hestia-border bg-hestia-surface p-6 shadow-xl sm:p-8">
        {done && <WizardHeader step={step} />}
        <div className="flex items-center gap-3">
          <img
            src={flame}
            alt=""
            className={`h-9 w-9 ${running ? "animate-pulse" : ""}`}
          />
          <h2 id="extraction-progress-title" className="text-xl">
            {done
              ? step === 1
                ? "Analysis complete"
                : "Review your skills"
              : failed
                ? "Analysis failed"
                : "Analyzing course materials"}
          </h2>
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
            <ol className="mt-5 flex flex-col gap-3">
              {PHASES.map((phase, i) => {
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
            <p className="mt-5 text-sm text-hestia-text-muted">
              This can take a while — large courses run many LLM calls. You can keep this open.
            </p>
          </div>
        )}

        {done && step === 1 && (
          <>
            <dl className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-3">
              <Stat label="Documents" value={result?.documentsProcessed} />
              <Stat label="Goals created" value={result?.goalsCreated} />
              <Stat label="Terminal competencies" value={result?.terminalCompetencies} />
            </dl>
            <div className="mt-6 flex justify-end">
              <button
                onClick={() => setStep(2)}
                className="rounded-md bg-hestia-primary px-4 py-2 text-sm font-semibold text-white transition hover:bg-hestia-primary-hover"
              >
                Review skills →
              </button>
            </div>
          </>
        )}

        {done && step === 2 && (
          <>
            <p className="mt-6 text-sm text-hestia-text-muted">
              Take a quick look at the terminal skills we extracted — rename or remove any that
              are off.
            </p>
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
            <div className="mt-6 flex items-center justify-between gap-3 border-t border-hestia-border pt-4">
              <button
                onClick={() => setStep(1)}
                className="rounded-md px-2 py-2 text-sm font-semibold text-hestia-text-muted transition hover:text-hestia-text"
              >
                ← Back
              </button>
              <button
                onClick={onClose}
                className="rounded-md bg-hestia-primary px-4 py-2 text-sm font-semibold text-white transition hover:bg-hestia-primary-hover"
              >
                Done
              </button>
            </div>
          </>
        )}

        {failed && (
          <>
            <p className="mt-6 rounded-md border border-hestia-danger/40 bg-hestia-danger/10 px-3 py-2 text-sm text-hestia-danger">
              {error}
            </p>
            <div className="mt-6 flex justify-end">
              <button
                onClick={onClose}
                className="rounded-md px-4 py-2 text-sm font-semibold text-hestia-text-muted transition hover:text-hestia-text"
              >
                Close
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function WizardHeader({ step }: { step: 1 | 2 }) {
  return (
    <div className="mb-5 flex items-center gap-2.5 border-b border-hestia-border pb-4">
      <span className="text-xs font-semibold uppercase tracking-wider text-hestia-text-muted">
        Step {step} of 2
      </span>
      <div className="flex items-center gap-1.5" aria-label={`Step ${step} of 2`}>
        {[1, 2].map((number) => (
          <span
            key={number}
            className={`h-1.5 w-1.5 rounded-full ${
              number <= step ? "bg-hestia-primary" : "bg-hestia-border"
            }`}
          />
        ))}
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
          <button
            type="button"
            onClick={() => setEditing(false)}
            className="rounded-md border border-hestia-border px-2.5 py-1 text-sm font-medium text-hestia-text transition hover:bg-hestia-primary-muted"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={!canSave}
            className="rounded-md bg-hestia-primary px-2.5 py-1 text-sm font-medium text-white transition hover:bg-hestia-primary-hover disabled:opacity-50"
          >
            Save
          </button>
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
        <button
          type="button"
          title="Rename skill"
          aria-label="Rename skill"
          onClick={() => setEditing(true)}
          className="flex h-8 w-8 items-center justify-center rounded-md text-hestia-text-muted opacity-0 transition focus-visible:opacity-100 group-hover:opacity-100 hover:bg-hestia-surface hover:text-hestia-text"
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
        </button>
        <button
          type="button"
          title="Delete skill"
          aria-label="Delete skill"
          disabled={deleting}
          onClick={onDelete}
          className="flex h-8 w-8 items-center justify-center rounded-md text-hestia-text-muted opacity-0 transition focus-visible:opacity-100 group-hover:opacity-100 hover:bg-hestia-danger hover:text-white disabled:opacity-50"
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
        </button>
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
      <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-hestia-primary text-white">
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
      <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-[1.5px] border-hestia-primary text-[0.7rem] font-semibold tabular-nums text-hestia-primary ring-2 ring-hestia-primary-muted">
        {index + 1}
      </span>
    );
  }
  return (
    <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-[1.5px] border-hestia-border text-[0.7rem] font-semibold tabular-nums text-hestia-text-muted/60">
      {index + 1}
    </span>
  );
}

function Stat({ label, value }: { label: string; value?: number }) {
  return (
    <div className="rounded-lg bg-hestia-bg px-3 py-2">
      <dt className="text-xs font-medium uppercase tracking-wide text-hestia-text-muted">
        {label}
      </dt>
      <dd className="mt-0.5 text-lg font-semibold tabular-nums text-hestia-text">
        {value ?? 0}
      </dd>
    </div>
  );
}
