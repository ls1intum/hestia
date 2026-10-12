import React from "react";
import { MODE_COLORS } from "@/lib/constants";

interface UnderstandingCheckStepsProps {
  allSteps: string[];
  /** Draws a step's question (raw step, shown text, class name); the caller decides whether it is editable */
  renderEditable: (rawStep: string, cleanContent: string, className: string) => React.ReactNode;
  /** The user's activity-to-goal mapping; when present, cards follow it instead of the raw steps */
  mappings?: { method: string; lgIds: string[] }[];
  /** Goal ids in display order, used to turn mapping ids into goal numbers */
  goalIds?: string[];
}

/** Splits an evaluate step ("5 min - Goal 1 & 2 - Quiz: question") into its goal numbers, activity and question. */
function parseEvaluateStep(s: string) {
  const noTime = s.replace(/^\d+\s*(?:min|m)[\s—:-]*/i, "").trim();
  const lgMatch = noTime.match(/^(?:(?:Prompt\s+)?(?:LG|Goal)\s*([\d\s&,and]+)|(Combined))\s*[·•\-]\s*\[?([^\]:]+)\]?:\s*(.*)/i);
  const lgFallback = !lgMatch ? noTime.match(/^(?:Prompt\s+)?(?:LG|Goal)\s*([\d\s&,and]+)[:\s]+(.*)/i) : null;
  const lgNum = lgMatch ? lgMatch[1]?.trim() : lgFallback ? lgFallback[1]?.trim() : null;
  return {
    lgNum,
    nums: (lgNum?.match(/\d+/g) || []).map(n => parseInt(n, 10)),
    isCombined: lgMatch ? !!lgMatch[2] : false,
    activity: lgMatch ? lgMatch[3].trim() : null,
    question: lgMatch ? lgMatch[4].trim() : lgFallback ? lgFallback[2].trim() : noTime,
  };
}

const formatGoalLabel = (nums: number[]) =>
  nums.length === 1 ? `Learning Goal ${nums[0]}` : `Learning Goals ${nums.slice(0, -1).join(", ")} & ${nums[nums.length - 1]}`;

/**
 * The Check Understanding block in view mode (timetable and Final Review): one card per goal, or per
 * mapped activity when the user mapped activities to goals. In edit mode the timetable shows
 * EvaluateMappingPanel instead, where activities are changed.
 */
export function UnderstandingCheckSteps({ allSteps, renderEditable, mappings, goalIds }: UnderstandingCheckStepsProps) {
  const groups = (mappings || [])
    .map(m => ({
      method: m.method,
      nums: m.lgIds.map(id => (goalIds || []).indexOf(id) + 1).filter(n => n > 0).sort((a, b) => a - b),
    }))
    .filter(g => g.nums.length > 0);

  if (groups.length > 0) {
    // One card per mapped activity; each step goes to the first card covering one of its goals
    const parsed = allSteps.map(s => ({ raw: s, ...parseEvaluateStep(s) }));
    const stepsByGroup = groups.map(() => [] as typeof parsed);
    const unmatched: typeof parsed = [];
    parsed.forEach(p => {
      const gi = groups.findIndex(g => p.nums.some(n => g.nums.includes(n)));
      if (gi >= 0) stepsByGroup[gi].push(p); else unmatched.push(p);
    });

    return (
      <div className="pl-4 space-y-2">
        <p className="text-xs font-mono uppercase tracking-widest text-muted-foreground mb-2">
          Understanding Check
        </p>
        {groups.map((g, gi) => (
          <div key={gi} className="rounded-lg text-sm overflow-hidden"
            style={{ border: '1px solid color-mix(in srgb, var(--hestia-text) 10%, transparent)', borderLeft: '2px solid var(--hestia-phase-evaluate)' }}>
            <div className="px-3 py-1.5 flex items-center gap-2" style={{ backgroundColor: 'color-mix(in srgb, var(--hestia-surface) 50%, transparent)' }}>
              <span className="font-mono text-xs font-bold shrink-0 uppercase tracking-widest opacity-70" style={{ color: 'var(--hestia-phase-evaluate)' }}>
                {formatGoalLabel(g.nums)}
              </span>
              <span className="inline-flex items-center whitespace-nowrap rounded-full h-5 px-2.5 text-[0.75rem] font-semibold shrink-0"
                style={{ backgroundColor: MODE_COLORS['evaluate'].badgeBg, color: MODE_COLORS['evaluate'].badgeText }}>
                {g.method}
              </span>
            </div>
            <div className="px-3 py-2 italic space-y-1.5" style={{ backgroundColor: 'var(--hestia-surface)' }}>
              {stepsByGroup[gi].length > 0 ? stepsByGroup[gi].map((p, i) => (
                <div key={i} className="flex items-start">
                  {renderEditable(p.raw, p.question, "leading-relaxed w-full")}
                </div>
              )) : (
                <span className="text-muted-foreground not-italic text-xs">No question generated yet for this activity.</span>
              )}
            </div>
          </div>
        ))}
        {unmatched.map((p, i) => (
          <div key={`u-${i}`} className="rounded-lg text-sm overflow-hidden px-3 py-2 italic flex items-start"
            style={{ backgroundColor: 'var(--hestia-surface)', border: '1px solid color-mix(in srgb, var(--hestia-text) 10%, transparent)', borderLeft: '2px solid var(--hestia-phase-evaluate)' }}>
            {renderEditable(p.raw, p.question, "leading-relaxed w-full")}
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="pl-4 space-y-2">
      <p className="text-xs font-mono uppercase tracking-widest text-muted-foreground mb-2">
        Understanding Check
      </p>
      {allSteps.map((s, i) => {
        const { lgNum, isCombined, activity, question } = parseEvaluateStep(s);

        return (
          <div key={i} className="rounded-lg text-sm overflow-hidden"
            style={{ border: '1px solid color-mix(in srgb, var(--hestia-text) 10%, transparent)', borderLeft: '2px solid var(--hestia-phase-evaluate)' }}>
            {(lgNum || isCombined) && (
              <div className="px-3 py-1.5 flex items-center gap-2" style={{ backgroundColor: 'color-mix(in srgb, var(--hestia-surface) 50%, transparent)' }}>
                <span className="font-mono text-xs font-bold shrink-0 uppercase tracking-widest opacity-70" style={{ color: 'var(--hestia-phase-evaluate)' }}>
                  {isCombined ? "Combined Goals" : `Learning Goal ${lgNum}`}
                </span>
                {activity && (
                  <span className="inline-flex items-center whitespace-nowrap rounded-full h-5 px-2.5 text-[0.75rem] font-semibold shrink-0"
                    style={{ backgroundColor: MODE_COLORS['evaluate'].badgeBg, color: MODE_COLORS['evaluate'].badgeText }}>
                    {activity}
                  </span>
                )}
              </div>
            )}
            <div className="px-3 py-2 italic flex items-start" style={{ backgroundColor: 'var(--hestia-surface)' }}>
              {renderEditable(s, question, "leading-relaxed w-full")}
            </div>
          </div>
        );
      })}
    </div>
  );
}
