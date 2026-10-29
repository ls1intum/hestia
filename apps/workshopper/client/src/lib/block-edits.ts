import type { ActivityBlock, ActivitySection } from "./workshop-generator";
import { parseStepDuration } from "./utils";

/*
 * Edits of a single timetable block, as pure functions (block in, new block out).
 * Steps are stored as text with a time prefix ("5 min - …"); section and block durations are kept
 * equal to the sum of their steps' minutes whenever steps change.
 */

const STEP_TIME = /^(\d+)\s*(?:min|m)(?:utes?)?\s*(?:—|-|–|:)?\s*(.*)/i;

const toMinutes = (value: string) => {
  const num = parseInt(value, 10);
  return isNaN(num) || num < 0 ? 0 : num;
};

const sumSteps = (steps: string[]) => steps.reduce((acc, st) => acc + parseStepDuration(st), 0);
const sumSections = (sections: ActivitySection[]) => sections.reduce((acc, sec) => acc + (sec.duration || 0), 0);

/** Maps one section; other sections stay as they are. */
const mapSection = (block: ActivityBlock, sectionIdx: number, fn: (sec: ActivitySection) => ActivitySection): ActivitySection[] =>
  (block.sections || []).map((sec, si) => (si === sectionIdx ? fn(sec) : sec));

/** Replaces a step's text. The editor hands back the full step, time included; a missing time prefix is kept from the old step. */
export function setStepText<B extends ActivityBlock>(block: B, sectionIdx: number, stepIdx: number, text: string): B {
  if (!block.sections) return block;
  return {
    ...block,
    sections: mapSection(block, sectionIdx, sec => ({
      ...sec,
      steps: (sec.steps || []).map((st, ti) => {
        if (ti !== stepIdx) return st;
        if (/^\d+\s*(?:min|m)\b/i.test(text)) return text;
        const match = st.match(STEP_TIME);
        return (match ? `${match[1]} min - ` : "") + text;
      }),
    })),
  };
}

/** Sets a step's minutes; its section and the block take the new total. */
export function setStepMinutes<B extends ActivityBlock>(block: B, sectionIdx: number, stepIdx: number, minutes: string): B {
  if (!block.sections) return block;
  const num = toMinutes(minutes);
  const sections = block.sections.map((sec, si) => {
    let steps = sec.steps || [];
    if (si === sectionIdx) {
      steps = steps.map((st, ti) => {
        if (ti !== stepIdx) return st;
        const match = st.match(STEP_TIME);
        return `${num} min - ${match ? match[2] : st}`;
      });
    }
    return { ...sec, steps, duration: steps.length > 0 ? sumSteps(steps) : (sec.duration || 0) };
  });
  return { ...block, sections, duration: sumSections(sections) };
}

/** Sets the block's minutes; distributes proportionally across sections (single-section: direct assignment). */
export function setBlockMinutes<B extends ActivityBlock>(block: B, minutes: string): B {
  const num = toMinutes(minutes);
  if (!block.sections || block.sections.length === 0) {
    return { ...block, duration: num };
  }
  if (block.sections.length === 1) {
    return { ...block, sections: [{ ...block.sections[0], duration: num }], duration: num };
  }
  // Multi-section: distribute proportionally, preserving the ratio between sections
  const total = sumSections(block.sections);
  let remaining = num;
  const sections = block.sections.map((sec, i) => {
    if (i === block.sections!.length - 1) {
      // Last section gets the remainder so the total is exact
      return { ...sec, duration: Math.max(0, remaining) };
    }
    const share = total > 0
      ? Math.min(Math.max(0, Math.round(((sec.duration || 0) / total) * num)), remaining)
      : Math.min(Math.max(0, Math.floor(num / block.sections!.length)), remaining);
    remaining -= share;
    return { ...sec, duration: share };
  });
  return { ...block, sections, duration: num };
}

/** Adds a 5-minute step to the last section (or a new section if there is none). */
export function addStep<B extends ActivityBlock>(block: B, text: string): B {
  const step = `5 min - ${text}`;
  const sections = block.sections ? [...block.sections] : [];
  if (sections.length === 0) {
    sections.push({ title: "", duration: 5, steps: [step], methods: [], materials: [] });
  } else {
    const last = sections[sections.length - 1];
    sections[sections.length - 1] = { ...last, steps: [...(last.steps || []), step], duration: (last.duration || 0) + 5 };
  }
  return { ...block, sections, duration: sumSections(sections) };
}

export function deleteStep<B extends ActivityBlock>(block: B, sectionIdx: number, stepIdx: number): B {
  if (!block.sections) return block;
  const sections = mapSection(block, sectionIdx, sec => {
    const steps = (sec.steps || []).filter((_, ti) => ti !== stepIdx);
    return { ...sec, steps, duration: sumSteps(steps) };
  });
  return { ...block, sections, duration: sumSections(sections) };
}

/** Removes an activity from the block and its sections (the steps stay). */
export function removeActivity<B extends ActivityBlock>(block: B, method: string): B {
  return {
    ...block,
    methods: (block.methods || []).filter(m => m !== method),
    sections: (block.sections || []).map(s => ({ ...s, methods: (s.methods || []).filter(m => m !== method) })),
  };
}

/** Sets one section's duration directly; the block total is recalculated from all sections. */
export function setSectionDuration<B extends ActivityBlock>(block: B, sectionIdx: number, minutes: string): B {
  const num = Math.max(1, toMinutes(minutes));
  const sections = (block.sections || []).map((sec, i) =>
    i === sectionIdx ? { ...sec, duration: num } : sec
  );
  return { ...block, sections, duration: sumSections(sections) };
}
