import type { ActivityBlock } from "./workshop-generator";

/**
 * What a block shows when expanded (timetable and Final Review), picked out of its step texts.
 * Steps are stored as text like "5 min - Prompt: …", so each phase has its own rules for which
 * step is the prompt and which are content. Both views use these rules, so they always agree.
 *
 * Each item says where an edit in the timetable goes: back into its stored `step`, into the block
 * objective, or nowhere (e.g. a session goal); `text` is what is shown.
 */
export interface ShownStep {
  /** The stored step text the item was taken from */
  step?: string;
  /** The item shows (and edits) the block objective */
  objective?: boolean;
  text: string;
}

export type BlockContent =
  | { kind: "goals"; goals: ShownStep[] }
  | { kind: "prompt"; prompt: ShownStep }
  | { kind: "cycle"; content: ShownStep[]; activity: ShownStep }
  | { kind: "summary"; takeaways: ShownStep[]; activity: ShownStep }
  | { kind: "evaluate"; steps: string[] }
  /** Break, buffer and custom blocks: no special layout */
  | { kind: "other" };

const TIME_PREFIX = /^\d+\s*(?:min|m)[\s—:-]*/i;

/** "5 min - Prompt: What is …" → "Prompt: What is …" */
export const stripStepTime = (step: string) => step.replace(TIME_PREFIX, "").trim();

/** The activities shown as badges: the block's own and its sections', without lecture/presentation. */
export function blockMethods(block: ActivityBlock): string[] {
  const phase = (block.phase || "").toUpperCase();
  if (phase === "SUMMARY" || phase === "ARRIVE" || phase === "BREAK" || phase === "BUFFER") {
    return [];
  }
  const raw = Array.from(new Set([
    ...(block.methods || []),
    ...(block.sections || []).flatMap(s => s.methods || []),
  ])).filter(m => m && !m.toLowerCase().includes("lecture") && !m.toLowerCase().includes("presentation"));
  // Drop names contained in a longer one ("Quiz" when "Quiz / Polls" is there)
  return raw.filter(m => !raw.some(other => other !== m && other.toLowerCase().includes(m.toLowerCase())));
}

/**
 * The item for a prompt or activity step: the step cleaned of its time and the given prefix, showing
 * the objective while the step is still empty. Without a step it is the objective itself.
 */
function promptItem(step: string | undefined, prefix: RegExp, objective: string): ShownStep {
  if (!step) return { objective: true, text: objective };
  return { step, text: stripStepTime(step).replace(prefix, "").trim() || objective };
}

export function blockContent(block: ActivityBlock, sessionGoals: string[]): BlockContent {
  const allSteps = (block.sections || []).flatMap(s => s.steps || []);
  const objective = block.objective || "";

  switch (block.phase) {
    case "ARRIVE": {
      // The session's goals; older blocks without them fall back to the goals written in the steps
      if (sessionGoals.length > 0) {
        return { kind: "goals", goals: sessionGoals.map(g => ({ step: allSteps.find(s => s.includes(g)), text: g })) };
      }
      const goalSteps = allSteps.filter(s => /learning goal|objective/i.test(s));
      const shown = goalSteps.length > 0
        ? goalSteps.map(s => ({ step: s, text: stripStepTime(s).replace(/^learning goal[s]?[:\s]*/i, "").trim() }))
        : allSteps.map(s => ({ step: s, text: stripStepTime(s) })).filter(g => g.text.length > 0);
      return { kind: "goals", goals: shown };
    }

    case "ACTIVATE": {
      const step = allSteps.find(s => /prompt|question|discuss|think|consider|reflect/i.test(s)) || allSteps[0];
      return { kind: "prompt", prompt: promptItem(step, /^prompt[:\s]*/i, objective) };
    }

    case "LEARNING_CYCLE": {
      // Content: the first section ("You explain"); activity: the prompt or task step
      const activityStep =
        allSteps.find(s => /^(?:\d+\s*(?:min|m)[\s—:-]*)?prompt[:\s]/i.test(s)) ||
        allSteps.find(s => /^(?:\d+\s*(?:min|m)[\s—:-]*)?(?:activity|task|scenario)[:\s]/i.test(s)) ||
        allSteps.find(s => /prompt|activity|task|scenario|discuss|question/i.test(s));
      const content = (block.sections?.[0]?.steps || [])
        .filter(s => s !== activityStep)
        .map(s => ({ step: s, text: stripStepTime(s).replace(/^explains?:\s*/i, "").trim() }));
      return { kind: "cycle", content, activity: promptItem(activityStep, /^(activity|prompt)[:\s]*/i, objective) };
    }

    case "SUMMARY": {
      const activityStep = allSteps.find(s => /prompt|one.minute|q&a|question|take.?away/i.test(s));
      const takeaways = allSteps
        .filter(s => s !== activityStep && !/^(\d+\s*(?:min|m)[\s—:-]*)?(activity|prompt|q&a|questions|logistics|thank)/i.test(s))
        .map(s => ({ step: s, text: stripStepTime(s).replace(/^explains?:\s*/i, "").trim() }));
      return { kind: "summary", takeaways, activity: promptItem(activityStep, /^(activity|prompt)[:\s]*/i, objective) };
    }

    case "EVALUATE":
      return { kind: "evaluate", steps: allSteps };

    default:
      return { kind: "other" };
  }
}
