import { useState } from "react";
import { toast } from "@/hooks/use-toast";
import { generateSession, errorMessage, handleAuthError } from "@/lib/api";
import type { ActivityBlock, ActivitySection, LearningGoalPlan, SkeletonBlock, WorkshopInput, WorkshopSession } from "@/lib/workshop-generator";
import type { DndActivityBlock, EvaluateMappings, TimetableEditing } from "./useTimetableEditing";

/** Asks for only the practice part of a block; its explain part is kept as it is. */
const PRACTICE_ONLY = "\nIMPORTANT: Only generate the interactive participant practice section and setup. Skip the 'Explain' or 'Lecture' phase since it was already covered.";

/** A section where participants do the activity ("Participants Practice"), not the lecturer's explain part. */
const isPracticeSection = (s: ActivitySection) => /activity|practice|application/i.test(s.title || "");

const withoutExplainSections = (sections: ActivitySection[]) =>
  sections.filter(s => !/explain|lecture|introduction|theory/i.test(s.title || ""));

/**
 * Positions of the practice sections an activity switch replaces: those that list the activity,
 * otherwise all practice sections (generated sections often don't list their activity).
 */
function practiceSectionsOf(block: ActivityBlock, activity: string): number[] {
  const practice = (block.sections || []).map((s, i) => ({ s, i })).filter(({ s }) => isPracticeSection(s));
  const ofActivity = practice.filter(({ s }) => (s.methods || []).includes(activity));
  return (ofActivity.length > 0 ? ofActivity : practice).map(({ i }) => i);
}

/**
 * Changes to a timetable block that need the LLM: switching or adding an activity, and the
 * understanding check's activity-to-goal mapping (edited in EvaluateMappingPanel). Each one regenerates the single block through
 * POST /session (a skeleton with one block) and merges the result into it.
 */
export function useBlockRegeneration({ editing, initialSession, goals, meta, onMetaChanged }: {
  editing: TimetableEditing;
  initialSession: WorkshopSession;
  goals: LearningGoalPlan[];
  meta: WorkshopInput;
  onMetaChanged?: (meta: WorkshopInput) => void;
}) {
  const { blocks, setBlocks, commitBlock } = editing;
  const [regeneratingBlockId, setRegeneratingBlockId] = useState<string | null>(null);
  const [hasConfirmedEvaluatePriorities, setHasConfirmedEvaluatePriorities] = useState(
    goals.length <= 2 || !!(meta.evaluateMappings && meta.evaluateMappings.length > 0)
  );

  /**
   * Regenerates one block: `request` builds the one-block skeleton and the settings to send,
   * `merge` combines the generated block with the current one. Resolves false if the request failed.
   */
  const regenerate = async (
    dndId: string,
    request: (block: DndActivityBlock) => { skeleton: SkeletonBlock; meta: WorkshopInput },
    merge: (current: DndActivityBlock, generated: ActivityBlock) => DndActivityBlock,
    messages: { success: { title: string; description: string }; failure: string },
  ): Promise<boolean> => {
    const block = blocks.find(b => b.dndId === dndId);
    if (!block) return false;
    setRegeneratingBlockId(dndId);
    try {
      const req = request(block);
      const generated = await generateSession(goals, req.meta, { sessionId: initialSession.id, blocks: [req.skeleton] });
      const updated = generated.blocks?.[0];
      if (updated) {
        setBlocks(prev => {
          const next = prev.map(b => (b.dndId === dndId ? merge(b, updated) : b));
          commitBlock(dndId, next);
          return next;
        });
        toast(messages.success);
      }
      return true;
    } catch (e) {
      if (!handleAuthError(e)) toast({ title: messages.failure, description: errorMessage(e, "The AI request failed."), variant: "destructive" });
      return false;
    } finally {
      setRegeneratingBlockId(null);
    }
  };

  /** The block's current sections as skeleton sections (titles and minutes only). */
  const skeletonSections = (block: DndActivityBlock) =>
    (block.sections || []).map(s => ({ title: s.title, duration: s.duration || 0 }));

  /**
   * Replaces one activity of a block (or adds it if the block didn't have it). When the block has
   * practice sections next to its explain part (a learning cycle), only the practice sections of
   * that activity are regenerated, so the content to teach stays as it is. Other blocks are
   * regenerated as a whole.
   */
  const switchActivity = (dndId: string, oldActivity: string, newActivity: string) => {
    let newMethods: string[] = [];
    let lgIndex = 0;
    /** Positions of the practice sections being replaced; empty when the whole block is regenerated */
    let replaced: number[] = [];
    return regenerate(dndId,
      block => {
        lgIndex = block.lgIndex ?? (block.phase === "LEARNING_CYCLE" ? 1 : 0);
        newMethods = [...(block.methods || [])];
        const i = newMethods.indexOf(oldActivity);
        if (i !== -1) newMethods[i] = newActivity; else newMethods.push(newActivity);
        replaced = practiceSectionsOf(block, oldActivity);
        const practiceOnly = replaced.length > 0 && replaced.length < (block.sections || []).length;
        if (!practiceOnly) replaced = [];
        const replacedSections = replaced.map(i => block.sections![i]);
        return {
          skeleton: practiceOnly
            ? {
                phase: block.phase,
                title: block.phaseLabel || block.phase,
                description: (block.objective || "") + PRACTICE_ONLY,
                lgIndex,
                duration: replacedSections.reduce((acc, s) => acc + (s.duration || 0), 0) || block.duration,
                sections: replacedSections.map(s => ({ title: s.title, duration: s.duration || 0 })),
              }
            : { phase: block.phase, title: block.phaseLabel || block.phase, description: block.objective, lgIndex, duration: block.duration, sections: skeletonSections(block) },
          meta: { ...meta, selectedActivities: practiceOnly ? [newActivity] : newMethods },
        };
      },
      (b, updated) => {
        if (replaced.length > 0) {
          // Swap the old practice sections for the new ones, where the first of them was
          const fresh = withoutExplainSections(updated.sections || []);
          const old = b.sections || [];
          const sections = [
            ...old.slice(0, replaced[0]).filter((_, i) => !replaced.includes(i)),
            ...fresh,
            ...old.slice(replaced[0]).filter((_, i) => !replaced.includes(i + replaced[0])),
          ];
          return {
            ...b,
            sections,
            duration: sections.reduce((acc, s) => acc + (s.duration || 0), 0),
            methods: newMethods,
          };
        }
        return {
          ...b,
          ...updated,
          // If the server returns a generic phase name as the label, keep the user's label
          phaseLabel: updated.phaseLabel && updated.phaseLabel !== updated.phase ? updated.phaseLabel : (b.phaseLabel || b.phase),
          methods: updated.methods || newMethods,
          dndId,
          lgIndex,
          blockId: b.blockId,
        };
      },
      { success: { title: "Activity updated", description: `Switched to ${newActivity}` }, failure: "Generation failed" },
    );
  };

  /** Adds a second activity to a block: generates only its practice part and appends it. */
  const addActivity = (dndId: string, methodToAdd: string) => regenerate(dndId,
    block => {
      // The existing practice section's length is the baseline for the new activity
      const practice = (block.sections || []).find(isPracticeSection);
      return {
        skeleton: {
          phase: block.phase,
          title: block.phaseLabel || block.phase,
          description: (block.objective || "") + PRACTICE_ONLY,
          lgIndex: block.lgIndex ?? (block.phase === "LEARNING_CYCLE" ? 1 : 0),
          duration: practice?.duration || Math.max(10, Math.floor((block.duration || 40) / 2)),
          sections: [], // no sections, so the AI generates a fresh practice loop
        },
        meta: { ...meta, selectedActivities: [methodToAdd] },
      };
    },
    (b, generated) => {
      // Append the new sections without any explain/lecture part, since the block already has one
      const added = withoutExplainSections(generated.sections || []);
      return {
        ...b,
        sections: [...(b.sections || []), ...added],
        duration: (b.duration || 0) + added.reduce((acc, s) => acc + (s.duration || 0), 0),
        methods: Array.from(new Set([...(b.methods || []), methodToAdd])),
      };
    },
    { success: { title: "Activity added", description: `Added ${methodToAdd}` }, failure: "Addition failed" },
  );

  /** Regenerates a Check Understanding block for new activity-to-goal mappings; resolves false if that failed. */
  const confirmEvaluateMappings = (dndId: string, mappings: EvaluateMappings) => {
    const newMeta = { ...meta, evaluateMappings: mappings };
    onMetaChanged?.(newMeta);
    setHasConfirmedEvaluatePriorities(true);
    return regenerate(dndId,
      block => ({
        skeleton: { phase: block.phase, title: block.phaseLabel || block.phase, description: block.objective, lgIndex: block.lgIndex ?? 0, duration: block.duration },
        meta: newMeta,
      }),
      (original, updated) => ({ ...updated, dndId: original.dndId, phaseLabel: original.phaseLabel || original.phase }),
      { success: { title: "Mappings updated", description: "Understanding Check regenerated." }, failure: "Update failed" },
    );
  };

  return {
    regeneratingBlockId, hasConfirmedEvaluatePriorities,
    switchActivity, addActivity, confirmEvaluateMappings,
  };
}
