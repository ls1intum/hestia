import { useCallback, useMemo, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  confirmSection as apiConfirmSection,
  unconfirmSection as apiUnconfirmSection,
} from "@/lib/api-client";
import { sectionsKey } from "@/hooks/use-sections";
import { taskAnswersKey } from "@/hooks/use-task-answers";
import type { Section, Task } from "@/lib/exam-helpers";
import { solveSection } from "@/lib/api-solve";

/**
 * DB-backed section confirmation.
 *
 * `confirmed_at` on the sections row is the source of truth. Confirming a
 * section also fires off a `solve-section` edge call so the LLM answers are
 * ready (or close to it) by the time the user clicks Ready. Unconfirming
 * deletes any cached answers for that section — the user is editing, so
 * those answers are stale by definition.
 *
 * In-flight background solves are tracked in a ref so callers (e.g. the
 * sendToEvaluation flow) can await them before flipping exam status, which
 * prevents duplicate LLM dispatch when the user rushes Ready.
 */
export const useSectionConfirmations = (
  examId: string | undefined,
  sections: Section[] | undefined,
  tasks: Task[] | undefined,
) => {
  const qc = useQueryClient();
  const inFlightRef = useRef<Map<string, Promise<unknown>>>(new Map());
  // Synthetic ids like "_unassigned" have no sections row. Their confirmation
  // is tracked in-memory only — they have no LLM pre-solve attached anyway
  // (the orphan bucket is solved by the orchestrator when Ready fires).
  const [syntheticConfirmed, setSyntheticConfirmed] = useState<Set<string>>(
    () => new Set(),
  );

  const sectionsById = useMemo(() => {
    const m = new Map<string, Section>();
    for (const s of sections ?? []) m.set(s.id, s);
    return m;
  }, [sections]);

  const isSynthetic = useCallback(
    (sectionId: string) => !sectionsById.has(sectionId),
    [sectionsById],
  );

  const isConfirmed = useCallback(
    (sectionId: string) =>
      isSynthetic(sectionId)
        ? syntheticConfirmed.has(sectionId)
        : !!sectionsById.get(sectionId)?.confirmed_at,
    [sectionsById, syntheticConfirmed, isSynthetic],
  );

  const writeConfirmedAt = useCallback(
    (sectionId: string, value: string | null) => {
      if (!examId) return;
      qc.setQueryData<Section[]>(sectionsKey(examId), (prev) =>
        (prev ?? []).map((s) =>
          s.id === sectionId ? { ...s, confirmed_at: value } : s,
        ),
      );
    },
    [examId, qc],
  );

  const dispatchSolve = useCallback(
    (sectionId: string) => {
      if (!examId) return;
      const promise = solveSection(examId, sectionId)
        .then(() => {
          // Refresh the answer cache so sendToEvaluation can see fresh rows.
          qc.invalidateQueries({ queryKey: taskAnswersKey(examId) });
        })
        .catch((err) => {
          console.error("solve-section background invocation failed", err);
        })
        .finally(() => {
          // Only clear if we're still the active dispatch for this section —
          // a rapid re-confirm replaces the entry and that newer one wins.
          if (inFlightRef.current.get(sectionId) === promise) {
            inFlightRef.current.delete(sectionId);
          }
        });
      inFlightRef.current.set(sectionId, promise);
    },
    [examId, qc],
  );

  const confirm = useCallback(
    async (sectionId: string) => {
      if (!examId) return;
      if (isConfirmed(sectionId)) return;
      if (isSynthetic(sectionId)) {
        setSyntheticConfirmed((prev) => {
          if (prev.has(sectionId)) return prev;
          const next = new Set(prev);
          next.add(sectionId);
          return next;
        });
        return;
      }
      const nowIso = new Date().toISOString();
      writeConfirmedAt(sectionId, nowIso);
      try {
        await apiConfirmSection(sectionId);
      } catch (error) {
        writeConfirmedAt(sectionId, null);
        console.error("confirm section failed", error);
        return;
      }
      dispatchSolve(sectionId);
    },
    [examId, isConfirmed, isSynthetic, writeConfirmedAt, dispatchSolve],
  );

  const unconfirm = useCallback(
    async (sectionId: string) => {
      if (!examId) return;
      if (!isConfirmed(sectionId)) return;
      if (isSynthetic(sectionId)) {
        setSyntheticConfirmed((prev) => {
          if (!prev.has(sectionId)) return prev;
          const next = new Set(prev);
          next.delete(sectionId);
          return next;
        });
        return;
      }
      writeConfirmedAt(sectionId, null);
      // The unconfirm endpoint clears confirmed_at AND drops the section's AI
      // answers in one atomic call. A late-arriving in-flight solve is
      // harmless: the server-side confirmed_at guard skips its insert.
      try {
        await apiUnconfirmSection(sectionId);
      } catch (error) {
        console.error("unconfirm section failed", error);
        writeConfirmedAt(sectionId, new Date().toISOString());
        return;
      }
      qc.invalidateQueries({ queryKey: taskAnswersKey(examId) });
    },
    [examId, isConfirmed, isSynthetic, writeConfirmedAt, qc],
  );

  const getInFlight = useCallback(
    () => Array.from(inFlightRef.current.values()),
    [],
  );

  return { isConfirmed, confirm, unconfirm, getInFlight };
};

export type SectionConfirmationsApi = ReturnType<typeof useSectionConfirmations>;
