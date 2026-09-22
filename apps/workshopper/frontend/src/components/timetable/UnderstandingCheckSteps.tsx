import React from "react";
import { ChevronDown, Loader2, Trash2 } from "lucide-react";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel,
  DropdownMenuSeparator, DropdownMenuTrigger, DropdownMenuSub, DropdownMenuSubTrigger, DropdownMenuSubContent
} from "@/components/ui/dropdown-menu";
import { ACTIVITY_GROUPS, MODE_COLORS } from "@/lib/constants";

interface UnderstandingCheckStepsProps {
  allSteps: string[];
  isEditMode: boolean;
  isRegenerating: boolean;
  onSwitchEvaluateActivity?: (lgNum: number, newActivity: string) => void;
  onDeleteStep: (sIdx: number, stIdx: number) => void;
  findStepIndices: (stepText: string) => { sIdx: number; stIdx: number } | null;
  renderEditable: (rawStep: string, cleanContent: string, className: string, style?: any) => React.ReactNode;
}

export function UnderstandingCheckSteps({
  allSteps,
  isEditMode,
  isRegenerating,
  onSwitchEvaluateActivity,
  onDeleteStep,
  findStepIndices,
  renderEditable
}: UnderstandingCheckStepsProps) {
  return (
    <div className="pl-4 space-y-2">
      <p className="text-xs font-mono uppercase tracking-widest text-muted-foreground mb-2">
        Understanding Check
      </p>
      {allSteps.map((s, i) => {
        const noTime = s.replace(/^\d+\s*(?:min|m)[\s—:-]*/i, "").trim();
        const lgMatch = noTime.match(/^(?:(?:Prompt\s+)?(?:LG|Goal)\s*([\d\s&,and]+)|(Combined))\s*[·•\-]\s*\[?([^\]:]+)\]?:\s*(.*)/i);
        const lgFallback = !lgMatch ? noTime.match(/^(?:Prompt\s+)?(?:LG|Goal)\s*([\d\s&,and]+)[:\s]+(.*)/i) : null;
        const lgNum = lgMatch ? lgMatch[1]?.trim() : lgFallback ? lgFallback[1]?.trim() : null;
        const isCombined = lgMatch ? !!lgMatch[2] : false;
        const activity = lgMatch ? lgMatch[3].trim() : null;
        const question = lgMatch ? lgMatch[4].trim() : lgFallback ? lgFallback[2].trim() : noTime;

        return (
          <div key={i} className="rounded-lg text-sm overflow-hidden"
            style={{ border: '1px solid color-mix(in srgb, var(--hestia-text) 10%, transparent)', borderLeft: '2px solid var(--hestia-phase-evaluate)' }}>
            {(lgNum || isCombined) && (
              <div className="px-3 py-1.5 flex items-center gap-2" style={{ backgroundColor: 'color-mix(in srgb, var(--hestia-surface) 50%, transparent)' }}>
                <span className="font-mono text-xs font-bold shrink-0 uppercase tracking-widest opacity-70" style={{ color: 'var(--hestia-phase-evaluate)' }}>
                  {isCombined ? "Combined Goals" : `Learning Goal ${lgNum}`}
                </span>
                {activity && (!isEditMode || !onSwitchEvaluateActivity || isCombined ? (
                  <span className="inline-flex items-center whitespace-nowrap rounded-full h-5 px-2.5 text-[0.75rem] font-semibold shrink-0"
                    style={{ backgroundColor: MODE_COLORS['evaluate'].badgeBg, color: MODE_COLORS['evaluate'].badgeText }}>
                    {activity}
                  </span>
                ) : (
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <div
                        role="button"
                        onClick={e => e.stopPropagation()}
                        className="inline-flex items-center gap-1 whitespace-nowrap rounded-full h-5 px-2.5 text-[0.75rem] font-semibold cursor-pointer hover:opacity-80 transition-opacity shrink-0"
                        style={{ backgroundColor: MODE_COLORS['evaluate'].badgeBg, color: MODE_COLORS['evaluate'].badgeText }}
                        title="Click to switch activity"
                      >
                        {isRegenerating ? <Loader2 className="h-3 w-3 animate-spin" /> : null}
                        {activity} <ChevronDown className="h-2.5 w-2.5 opacity-50" />
                      </div>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="start" className="w-48">
                      <DropdownMenuLabel className="text-xs font-mono uppercase text-muted-foreground">LG{lgNum} Activity</DropdownMenuLabel>
                      <DropdownMenuSeparator />
                      {ACTIVITY_GROUPS.map(group => (
                        <DropdownMenuSub key={group.label}>
                          <DropdownMenuSubTrigger className="flex items-center gap-2">
                            <span>{group.groupEmoji}</span><span>{group.label}</span>
                          </DropdownMenuSubTrigger>
                          <DropdownMenuSubContent>
                            {group.activities.map(act => (
                              <DropdownMenuItem key={act.name} onClick={e => { e.stopPropagation(); onSwitchEvaluateActivity(parseInt(lgNum || "0"), act.name); }}>
                                {act.name}
                              </DropdownMenuItem>
                            ))}
                          </DropdownMenuSubContent>
                        </DropdownMenuSub>
                      ))}
                      <DropdownMenuSeparator />
                      <DropdownMenuItem
                        className="text-destructive focus:bg-destructive/10 focus:text-destructive"
                        onClick={e => {
                          e.stopPropagation();
                          const indices = findStepIndices(s);
                          if (indices) onDeleteStep(indices.sIdx, indices.stIdx);
                        }}
                      >
                        <Trash2 className="h-4 w-4 mr-2" />
                        Delete Activity
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                ))}
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
