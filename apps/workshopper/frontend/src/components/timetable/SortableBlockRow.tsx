import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  GripVertical, ChevronDown, ChevronUp,
  Trash2, Plus, Loader2, Pencil, X, Check,
} from "lucide-react";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger, DropdownMenuSeparator,
  DropdownMenuSub, DropdownMenuSubTrigger, DropdownMenuSubContent, DropdownMenuLabel,
} from "@/components/ui/dropdown-menu";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { InlineEditText } from "./InlineEditText";
import { InlineEditableStep } from "./InlineEditableStep";
import { phaseEmojis, getStepEmoji, DEFAULT_ACTIVITIES, ACTIVITY_GROUPS, MODE_COLORS, getPhaseMode, getSectionMode, getStepMode } from "@/lib/constants";
import { EditTarget, DndActivityBlock } from "../WorkshopGeneratedTimetable";
import { WorkshopInput } from "@/lib/workshop-generator";

export function SortableBlockRow({
  block, isExpanded, editing, meta, isRegenerating, selectedActivities,
  onToggleExpand, onEditTitle, onSaveTitle, onEditStep, onSaveStep,
  onEditStepTime, onSaveStepTime,
  onEditBlockDuration, onSaveBlockDuration, onEditSectionDuration, onSaveSectionDuration,
  onDeleteBlock, onSwitchActivity, onDeleteActivity, onAddActivity, onAddStep, onDeleteStep,
  isEditMode = false, onToggleEditMode, onCancelEditMode, onSwitchEvaluateActivity,
  goals, needsEvaluatePriority, onConfirmEvaluateMappings, onResetEvaluatePriority
}: {
  block: DndActivityBlock;
  isExpanded: boolean;
  editing: EditTarget;
  meta: WorkshopInput;
  isRegenerating: boolean;
  selectedActivities: string[];
  onToggleExpand: () => void;
  onEditTitle: () => void;
  onSaveTitle: (v: string) => void;
  onEditStep: (sectionIdx: number, stepIdx: number) => void;
  onSaveStep: (sectionIdx: number, stepIdx: number, v: string) => void;
  onEditStepTime: (sectionIdx: number, stepIdx: number) => void;
  onSaveStepTime: (sectionIdx: number, stepIdx: number, v: string) => void;
  onEditBlockDuration: () => void;
  onSaveBlockDuration: (v: string) => void;
  onEditSectionDuration: (sectionIdx: number) => void;
  onSaveSectionDuration: (sectionIdx: number, v: string) => void;
  onDeleteBlock: () => void;
  onSwitchActivity: (oldMethod: string, newMethod: string) => void;
  onDeleteActivity: (method: string) => void;
  onAddActivity: (method: string) => void;
  onAddStep: (text: string) => void;
  onDeleteStep: (sectionIdx: number, stepIdx: number) => void;
  isEditMode?: boolean;
  onToggleEditMode?: () => void;
  onCancelEditMode?: () => void;
  onSwitchEvaluateActivity?: (lgNum: number, newActivity: string) => void;
  goals?: import('@/lib/workshop-generator').LearningGoalPlan[];
  needsEvaluatePriority?: boolean;
  onConfirmEvaluateMappings?: (mappings: { method: string; lgIds: string[] }[]) => void;
  onResetEvaluatePriority?: () => void;
}) {
  const [boxes, setBoxes] = useState<{ id: string, method: string, lgIds: string[] }[]>(() => {
    if (meta.evaluateMappings && meta.evaluateMappings.length > 0) {
      return meta.evaluateMappings.map((m, i) => ({ id: `box-${Date.now()}-${i}`, method: m.method, lgIds: m.lgIds }));
    }
    return [{ id: `box-${Date.now()}`, method: 'Quiz', lgIds: [] }];
  });
  
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: block.dndId
  });
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.45 : 1,
    zIndex: isDragging ? 10 : undefined,
  };

  const mode = getPhaseMode(block.phase);
  const modeColors = MODE_COLORS[mode];
  const isBreak = mode === "break";

  const parentCardStyle: React.CSSProperties = {
    backgroundColor: needsEvaluatePriority ? 'rgba(239,68,68,0.05)' : modeColors.bgTint,
    border: needsEvaluatePriority ? '1.5px solid var(--hestia-destructive)' : (isBreak ? `1.5px dashed ${modeColors.border}` : `1px solid var(--hestia-border)`),
    borderLeft: needsEvaluatePriority ? '4px solid var(--hestia-destructive)' : (isBreak ? `1.5px dashed ${modeColors.border}` : `2px solid ${modeColors.border}`),
    boxShadow: isBreak ? "none" : "0 1px 4px rgba(0,0,0,0.07)",
    borderRadius: "12px",
  };
  const rawMethods = Array.from(new Set([
    ...(block.methods || []),
    ...(block.sections || []).flatMap(s => s.methods || []),
  ])).filter(m => m && !m.toLowerCase().includes("lecture") && !m.toLowerCase().includes("presentation"));

  const allMethods = rawMethods.filter(m => {
    return !rawMethods.some(other => other !== m && other.toLowerCase().includes(m.toLowerCase()));
  });

  const isTitleEditing = editing?.type === "title" && editing.blockId === block.dndId;

  return (
    <div ref={setNodeRef} style={style} className="mb-3">
      <Collapsible open={isExpanded} onOpenChange={onToggleExpand}>
        <div style={parentCardStyle} className={`flex flex-col p-3 transition-colors relative ${isDragging ? "ring-2 ring-primary/30" : ""}`}>
          {/* Main row */}
          <div className="flex items-center gap-3">
            {/* Drag handle */}
            <div {...attributes} {...listeners} className="cursor-grab active:cursor-grabbing text-muted-foreground hover:text-foreground shrink-0 touch-none">
              <GripVertical className="h-5 w-5" />
            </div>

            {/* Fixed-Width Time Column */}
            <div className="w-[52px] shrink-0 flex items-center justify-start">
              <div
                className="px-2 py-0.5 rounded-md text-[11px] font-mono whitespace-nowrap"
                style={{
                  backgroundColor: block.duration >= 10 ? 'rgba(134,92,29,0.10)' : 'rgba(0,0,0,0.05)',
                  color: block.duration >= 10 ? 'var(--hestia-primary)' : 'var(--hestia-text-muted)',
                  fontWeight: block.duration >= 10 ? 700 : 500,
                }}
              >
                {block.duration}m
              </div>
            </div>

            {/* Emoji + Title + Methods */}
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-base shrink-0">{phaseEmojis[block.phase] || "✨"}</span>
                <InlineEditText
                  value={block.phaseLabel || block.phase}
                  editing={isTitleEditing}
                  alwaysEdit={isEditMode}
                  onStartEdit={onEditTitle}
                  onSave={onSaveTitle}
                  className="font-body font-semibold text-sm"
                  disabled={false}
                />
                {/* Time Indication Bar */}
                <div className="flex items-center ml-1">
                  <div
                    style={{
                      width: Math.max(12, block.duration * 2.5),
                      height: 4,
                      backgroundColor: modeColors.border,
                      opacity: 0.3,
                      borderRadius: 9999,
                    }}
                  />
                </div>
                {needsEvaluatePriority && (
                  <span className="ml-2 text-[10px] font-bold uppercase tracking-wider text-destructive bg-destructive/10 px-2 py-0.5 rounded-full">
                    Input Needed
                  </span>
                )}
              </div>
            </div>

            {/* Duration + action icons + expand (rightmost) */}
            <div className="flex items-center gap-1.5 shrink-0">


              {/* Activity tags (Methods) */}
              {(allMethods.length > 0) && (
                <div className="flex items-center gap-1 mr-1">
                  {allMethods.map((m, j) => (isEditMode && block.phase !== "EVALUATE") ? (
                    <DropdownMenu key={j}>
                      <DropdownMenuTrigger asChild>
                        <div
                          role="button"
                          onClick={e => e.stopPropagation()}
                          onPointerDown={e => e.stopPropagation()}
                          style={{ backgroundColor: modeColors.badgeBg, color: modeColors.badgeText }}
                          className="inline-flex items-center gap-1 whitespace-nowrap rounded-full h-5 px-2.5 text-[0.75rem] font-semibold cursor-pointer hover:opacity-80 transition-opacity shrink-0"
                          title="Click to switch activity"
                        >
                          {isRegenerating ? <Loader2 className="h-3 w-3 animate-spin" /> : null}
                          {m} <ChevronDown className="h-2.5 w-2.5 opacity-50" />
                        </div>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent onClick={e => e.stopPropagation()} className="w-52">
                        {/* Grouped activities */}
                        {ACTIVITY_GROUPS.map(group => {
                          const groupActivities = Array.from(new Set([
                            ...group.activities.map(a => a.name),
                            // include selected activities that belong to this group
                            ...selectedActivities.filter(sa => group.activities.some(a => a.name === sa)),
                          ]));
                          return (
                            <DropdownMenuSub key={group.label}>
                              <DropdownMenuSubTrigger className="flex items-center gap-2">
                                <span>{group.groupEmoji}</span>
                                <span>{group.label}</span>
                              </DropdownMenuSubTrigger>
                              <DropdownMenuSubContent>
                                {groupActivities.map(act => (
                                  <DropdownMenuItem key={act} onClick={e => { e.stopPropagation(); onSwitchActivity(m, act); }}>
                                    {act}
                                  </DropdownMenuItem>
                                ))}
                              </DropdownMenuSubContent>
                            </DropdownMenuSub>
                          );
                        })}
                        {/* Custom (user-added) activities not in any group */}
                        {(() => {
                          const allGrouped = ACTIVITY_GROUPS.flatMap(g => g.activities.map(a => a.name));
                          const custom = Array.from(new Set([...selectedActivities, ...DEFAULT_ACTIVITIES])).filter(a => !allGrouped.includes(a));
                          return custom.length > 0 ? (
                            <DropdownMenuSub>
                              <DropdownMenuSubTrigger className="flex items-center gap-2">
                                <span>✨</span><span>Other</span>
                              </DropdownMenuSubTrigger>
                              <DropdownMenuSubContent>
                                {custom.map(act => (
                                  <DropdownMenuItem key={act} onClick={e => { e.stopPropagation(); onSwitchActivity(m, act); }}>
                                    {act}
                                  </DropdownMenuItem>
                                ))}
                              </DropdownMenuSubContent>
                            </DropdownMenuSub>
                          ) : null;
                        })()}
                        <DropdownMenuSeparator />
                        <DropdownMenuItem
                          className="text-destructive focus:bg-destructive/10 focus:text-destructive"
                          onClick={e => { e.stopPropagation(); onDeleteActivity(m); }}
                        >
                          <Trash2 className="h-4 w-4 mr-2" />
                          Delete Activity
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  ) : (
                    <div
                      style={{ backgroundColor: modeColors.badgeBg, color: modeColors.badgeText }}
                      className="inline-flex items-center gap-1 whitespace-nowrap rounded-full h-5 px-2.5 text-[0.75rem] font-semibold shrink-0"
                    >
                      {m}
                    </div>
                  ))}
                  {isEditMode && (
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <Button variant="ghost" size="icon" className="h-6 w-6 text-muted-foreground hover:text-primary hover:bg-primary/10 shrink-0" title="Add activity method">
                          <Plus className="h-3.5 w-3.5" />
                        </Button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent onClick={e => e.stopPropagation()} className="w-52">
                        {ACTIVITY_GROUPS.map(group => {
                          const groupActivities = Array.from(new Set([
                            ...group.activities.map(a => a.name),
                            ...selectedActivities.filter(sa => group.activities.some(a => a.name === sa)),
                          ]));
                          return (
                            <DropdownMenuSub key={group.label}>
                              <DropdownMenuSubTrigger className="flex items-center gap-2">
                                <span>{group.groupEmoji}</span>
                                <span>{group.label}</span>
                              </DropdownMenuSubTrigger>
                              <DropdownMenuSubContent>
                                {groupActivities.map(act => (
                                  <DropdownMenuItem key={act} onClick={e => { e.stopPropagation(); onAddActivity(act); }}>
                                    Add {act}
                                  </DropdownMenuItem>
                                ))}
                              </DropdownMenuSubContent>
                            </DropdownMenuSub>
                          );
                        })}
                        {(() => {
                          const allGrouped = ACTIVITY_GROUPS.flatMap(g => g.activities.map(a => a.name));
                          const custom = Array.from(new Set([...selectedActivities, ...DEFAULT_ACTIVITIES])).filter(a => !allGrouped.includes(a));
                          return custom.length > 0 ? (
                            <DropdownMenuSub>
                              <DropdownMenuSubTrigger className="flex items-center gap-2">
                                <span>✨</span><span>Other</span>
                              </DropdownMenuSubTrigger>
                              <DropdownMenuSubContent>
                                {custom.map(act => (
                                  <DropdownMenuItem key={act} onClick={e => { e.stopPropagation(); onAddActivity(act); }}>
                                    Add {act}
                                  </DropdownMenuItem>
                                ))}
                              </DropdownMenuSubContent>
                            </DropdownMenuSub>
                          ) : null;
                        })()}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  )}
                </div>
              )}

              {isEditMode && (
                <Button
                  variant="ghost" size="icon"
                  className="h-7 w-7 transition-colors shrink-0 text-destructive bg-destructive/10 hover:bg-destructive/20"
                  onClick={e => { e.stopPropagation(); onCancelEditMode?.(); }}
                  title="Undo block edits"
                >
                  <X className="h-3.5 w-3.5" />
                </Button>
              )}
              {/* Edit icon — pencil to enter, check to exit */}
              <Button
                variant="ghost" size="icon"
                className={`h-7 w-7 transition-colors shrink-0 ${isEditMode
                    ? "text-primary bg-primary/10 hover:bg-primary/20"
                    : "text-muted-foreground/40 hover:text-primary hover:bg-primary/10"
                  }`}
                onClick={e => { e.stopPropagation(); onToggleEditMode?.(); }}
                title={isEditMode ? "Done editing" : "Edit block"}
              >
                {isEditMode ? <Check className="h-3.5 w-3.5" /> : <Pencil className="h-3.5 w-3.5" />}
              </Button>

              {/* Delete icon */}
              <Button
                variant="ghost" size="icon"
                className="h-7 w-7 text-muted-foreground/40 hover:text-destructive hover:bg-destructive/10 transition-colors shrink-0"
                onClick={e => { e.stopPropagation(); onDeleteBlock(); }}
                title="Delete block"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </Button>

              {/* Expand chevron — rightmost */}
              {block.phase !== "BREAK" && block.phase !== "BUFFER" ? (
                <CollapsibleTrigger asChild>
                  <Button variant="ghost" size="icon" className="h-8 w-8">
                    {isExpanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                  </Button>
                </CollapsibleTrigger>
              ) : (
                <div className="h-8 w-8" />
              )}
            </div>
          </div>

          {/* Expanded: sections and steps */}
          <CollapsibleContent className="mt-3 pb-1 relative z-0">
            {(() => {
              const phase = block.phase;
              const allSteps = (block.sections || []).flatMap(s => s.steps || []);

              // Helper to find the absolute indices of a step for saving
              const findStepIndices = (targetStepText: string) => {
                const sections = block.sections || [];
                for (let sIdx = 0; sIdx < sections.length; sIdx++) {
                  const sec = sections[sIdx];
                  const steps = sec.steps || [];
                  for (let stIdx = 0; stIdx < steps.length; stIdx++) {
                    if (steps[stIdx] === targetStepText) {
                      return { sIdx, stIdx };
                    }
                  }
                }
                return null;
              };

              const renderEditable = (rawStep: string, cleanContent: string, className: string, style?: any) => {
                const indices = findStepIndices(rawStep);
                if (!indices) return <span className={className} style={style}>{cleanContent}</span>;
                return (
                  <InlineEditableStep
                    text={rawStep}
                    cleanText={cleanContent}
                    isEditMode={isEditMode}
                    onSave={(newText) => onSaveStep(indices.sIdx, indices.stIdx, newText)}
                    className={className}
                    style={style}
                  />
                );
              };

              // ── ARRIVE / Welcome ───────────────
              if (phase === "ARRIVE") {
                const rawGoals = meta?.learningGoals && meta.learningGoals.length > 0
                  ? meta.learningGoals
                  : allSteps
                    .filter((s: string) => /learning goal|objective/i.test(s))
                    .map((s: string) => s.replace(/^\d+\s*(?:min|m)[\s—:-]*/i, "").replace(/^learning goal[s]?[:\s]*/i, "").trim());
                const goals = rawGoals.length > 0 ? rawGoals : allSteps
                  .map((s: string) => s.replace(/^\d+\s*(?:min|m)[\s—:-]*/i, "").trim())
                  .filter((s: string) => s.length > 0);

                return (
                  <div className="pl-4 space-y-1.5">
                    <p className="text-[10px] font-mono uppercase tracking-widest text-muted-foreground mb-2">Learning Goals</p>
                    {goals.map((g: string, i: number) => {
                      const origStep = allSteps.find(s => s.includes(g)) || g;
                      return (
                        <div key={i} className="flex items-start gap-2 px-3 py-1.5 rounded-lg text-sm"
                          style={{ backgroundColor: 'var(--hestia-surface)', border: '1px solid color-mix(in srgb, var(--hestia-text) 10%, transparent)' }}>
                          <span className="font-mono text-xs font-bold shrink-0 mt-0.5 whitespace-nowrap" style={{ color: 'var(--hestia-primary)' }}>Learning Goal {i + 1}</span>
                          {renderEditable(origStep, g, "leading-relaxed w-full")}
                        </div>
                      );
                    })}
                  </div>
                );
              }

              // ── ACTIVATE: show the activity prompt question ──────────────────
              if (phase === "ACTIVATE") {
                const promptStep = allSteps.find(s =>
                  /prompt|question|discuss|think|consider|reflect/i.test(s)
                ) || allSteps[0] || block.objective;
                const clean = promptStep?.replace(/^\d+\s*(?:min|m)[\s—:-]*/i, "").replace(/^prompt[:\s]*/i, "").trim();
                return (
                  <div className="pl-4 space-y-1.5">
                    <p className="text-[10px] font-mono uppercase tracking-widest text-muted-foreground mb-2">Activity Prompt</p>
                    <div className="px-3 py-2 rounded-lg text-sm italic leading-relaxed"
                      style={{ backgroundColor: 'var(--hestia-surface)', borderLeft: '2px dashed var(--hestia-phase-evaluate)', border: '1px solid color-mix(in srgb, var(--hestia-text) 10%, transparent)' }}>
                      {renderEditable(promptStep, clean || block.objective, "w-full")}
                    </div>
                  </div>
                );
              }

              // ── LEARNING_CYCLE: content checklist + activity prompt ───────────
              if (phase === "LEARNING_CYCLE") {
                const sections = block.sections || [];
                const contentSteps = sections.length > 0 ? (sections[0].steps || []) : [];
                const activityStep = allSteps.find(s =>
                  /^(?:\d+\s*(?:min|m)[\s—:-]*)?prompt[:\s]/i.test(s)
                ) || allSteps.find(s =>
                  /^(?:\d+\s*(?:min|m)[\s—:-]*)?(?:activity|task|scenario)[:\s]/i.test(s)
                ) || allSteps.find(s =>
                  /prompt|activity|task|scenario|discuss|question/i.test(s)
                );
                const cleanActivity = activityStep
                  ?.replace(/^\d+\s*(?:min|m)[\s—:-]*/i, "")
                  .replace(/^(activity|prompt)[:\s]*/i, "")
                  .trim();

                return (
                  <div className="pl-4 space-y-3">
                    {contentSteps.length > 0 && (
                      <div>
                        <p className="text-[10px] font-mono uppercase tracking-widest text-muted-foreground mb-2">Content to Teach</p>
                        <div className="space-y-1">
                          {contentSteps.map((s, i) => {
                            const clean = s.replace(/^\d+\s*(?:min|m)[\s—:-]*/i, "").replace(/^explain[s]?:?\s*/i, "").trim();
                            return (
                              <div key={i} className="flex items-start gap-2 px-3 py-1.5 rounded-lg text-sm"
                                style={{ backgroundColor: 'var(--hestia-surface)', border: '1px solid color-mix(in srgb, var(--hestia-text) 10%, transparent)' }}>
                                <span className="shrink-0 mt-0.5 text-muted-foreground font-mono">{i + 1}.</span>
                                {renderEditable(s, clean, "leading-relaxed w-full")}
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    )}
                    {(cleanActivity || block.objective) && (
                      <div>
                        <p className="text-[10px] font-mono uppercase tracking-widest text-muted-foreground mb-2">
                          Activity{allMethods.length > 0 ? ` · ${allMethods[0]}` : ""}
                        </p>
                        <div className="px-3 py-2 rounded-lg text-sm italic leading-relaxed flex items-start"
                          style={{ backgroundColor: 'var(--hestia-surface)', borderLeft: '2px dashed var(--hestia-phase-setup)', border: '1px solid color-mix(in srgb, var(--hestia-text) 10%, transparent)' }}>
                          {renderEditable(activityStep || block.objective, cleanActivity || block.objective, "w-full")}
                        </div>
                      </div>
                    )}
                  </div>
                );
              }

              // ── SUMMARY: key takeaways + activity prompt ─────────────────────
              if (phase === "SUMMARY") {
                const takeawaySteps = allSteps.filter(s =>
                  !/^(\d+\s*(?:min|m)[\s—:-]*)?(activity|prompt|q&a|questions|logistics|thank)/i.test(s)
                );
                const activityStep = allSteps.find(s =>
                  /prompt|one.minute|q&a|question|take.?away/i.test(s)
                );
                const cleanActivity = activityStep
                  ?.replace(/^\d+\s*(?:min|m)[\s—:-]*/i, "")
                  .replace(/^(activity|prompt)[:\s]*/i, "")
                  .trim();

                return (
                  <div className="pl-4 space-y-3">
                    {takeawaySteps.length > 0 && (
                      <div>
                        <p className="text-[10px] font-mono uppercase tracking-widest text-muted-foreground mb-2">Key Takeaways</p>
                        <div className="space-y-1">
                          {takeawaySteps.map((s, i) => {
                            const clean = s.replace(/^\d+\s*(?:min|m)[\s—:-]*/i, "").replace(/^explain[s]?:?\s*/i, "").trim();
                            return (
                              <div key={i} className="flex items-start gap-2 px-3 py-1.5 rounded-lg text-sm"
                                style={{ backgroundColor: 'var(--hestia-surface)', border: '1px solid color-mix(in srgb, var(--hestia-text) 10%, transparent)' }}>
                                <span className="shrink-0 mt-0.5" style={{ color: 'var(--hestia-primary)' }}>✦</span>
                                {renderEditable(s, clean, "leading-relaxed w-full")}
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    )}
                    {(cleanActivity || block.objective) && (
                      <div>
                        <p className="text-[10px] font-mono uppercase tracking-widest text-muted-foreground mb-2">
                          Activity{allMethods.length > 0 ? ` · ${allMethods[0]}` : ""}
                        </p>
                        <div className="px-3 py-2 rounded-lg text-sm italic leading-relaxed flex items-start"
                          style={{ backgroundColor: 'var(--hestia-surface)', borderLeft: '2px dashed var(--hestia-primary)', border: '1px solid color-mix(in srgb, var(--hestia-text) 10%, transparent)' }}>
                          {renderEditable(activityStep || block.objective, cleanActivity || block.objective, "w-full")}
                        </div>
                      </div>
                    )}
                  </div>
                );
              }

              // ── EVALUATE: one prompt per LG ─────────────────────
              if (phase === "EVALUATE") {
                if ((needsEvaluatePriority || isEditMode) && goals) {
                  const validGoals = goals;

                  // Compute unassigned LGs
                  const assignedLgIds = new Set(boxes.flatMap(b => b.lgIds));
                  const unassignedLgs = validGoals.filter(g => g.id && !assignedLgIds.has(g.id));

                  const isOverLimit = block.duration <= 10 && boxes.length > 2;

                  return (
                    <div className="pl-4 space-y-4 pt-2">
                      <div className="flex items-start gap-2 rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 mb-4">
                        <div className="flex-1">
                          <h4 className="text-sm font-semibold text-destructive uppercase tracking-widest mb-1 font-mono">Action Required</h4>
                          <p className="text-sm text-foreground/80 leading-relaxed">
                            You have {validGoals.length} learning goals. To fit them in a {block.duration}-minute check understanding block,
                            please group them into activities. Drag your learning goals on the left into the activity boxes on the right.
                          </p>
                        </div>
                      </div>

                      <div className="flex gap-4 items-start">
                        {/* LEFT: Draggable LGs */}
                        <div className="w-1/3 flex flex-col gap-2 shrink-0">
                          <div className="text-xs font-mono font-bold text-muted-foreground uppercase tracking-widest mb-1">Unassigned Goals</div>
                          {unassignedLgs.length === 0 ? (
                            <div className="text-xs text-muted-foreground/60 italic p-3 border border-dashed rounded-lg text-center">All goals assigned!</div>
                          ) : (
                            unassignedLgs.map((g, i) => (
                              <div
                                key={g.id}
                                draggable
                                onDragStart={(e) => {
                                  e.dataTransfer.setData("text/plain", g.id!);
                                  e.dataTransfer.effectAllowed = "move";
                                }}
                                className="text-xs font-body leading-snug p-3 bg-card border shadow-sm rounded-lg cursor-grab active:cursor-grabbing hover:border-primary/50 transition-colors"
                              >
                                <span className="font-mono font-bold opacity-50 mr-1.5">LG{validGoals.findIndex(vg => vg.id === g.id) + 1}</span>
                                {g.goal}
                              </div>
                            ))
                          )}
                        </div>

                        {/* RIGHT: Activity Boxes */}
                        <div className="flex-1 flex flex-col gap-3">
                          <div className="flex items-center justify-between mb-1">
                            <div className="text-xs font-mono font-bold text-muted-foreground uppercase tracking-widest">Activity Boxes</div>
                            <Button 
                              variant="ghost" 
                              size="sm" 
                              className="h-6 px-2 text-xs text-primary hover:bg-primary/10"
                              onClick={() => {
                                setBoxes(prev => [...prev, { id: `box-${Date.now()}`, method: 'Quiz', lgIds: [] }]);
                              }}
                            >
                              <Plus className="h-3 w-3 mr-1" /> Add Box
                            </Button>
                          </div>
                          
                          {isOverLimit && (
                            <div className="text-xs text-amber-600 bg-amber-500/10 p-2 rounded border border-amber-500/20 leading-tight">
                              Warning: {boxes.length} activities might be too much for a {block.duration}-minute block.
                            </div>
                          )}

                          {boxes.map((box, bIdx) => (
                            <div
                              key={box.id}
                              className="bg-card border rounded-xl overflow-hidden shadow-sm transition-colors"
                              onDragOver={(e) => {
                                e.preventDefault();
                                e.dataTransfer.dropEffect = "move";
                                e.currentTarget.style.borderColor = "var(--hestia-primary)";
                                e.currentTarget.style.backgroundColor = "color-mix(in srgb, var(--hestia-primary) 5%, var(--hestia-surface))";
                              }}
                              onDragLeave={(e) => {
                                e.currentTarget.style.borderColor = "";
                                e.currentTarget.style.backgroundColor = "";
                              }}
                              onDrop={(e) => {
                                e.preventDefault();
                                e.currentTarget.style.borderColor = "";
                                e.currentTarget.style.backgroundColor = "";
                                const lgId = e.dataTransfer.getData("text/plain");
                                if (lgId) {
                                  setBoxes(prev => prev.map(b => {
                                    // Remove from any box
                                    const filtered = b.lgIds.filter(id => id !== lgId);
                                    // Add to this box
                                    if (b.id === box.id) return { ...b, lgIds: [...filtered, lgId] };
                                    return { ...b, lgIds: filtered };
                                  }));
                                }
                              }}
                            >
                              <div className="bg-muted/30 px-3 py-2 flex items-center justify-between border-b">
                                <DropdownMenu>
                                  <DropdownMenuTrigger asChild>
                                    <div className="inline-flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-bold uppercase tracking-wider rounded bg-primary/10 text-primary cursor-pointer hover:bg-primary/20 transition-colors">
                                      {box.method} <ChevronDown className="h-3 w-3 opacity-50" />
                                    </div>
                                  </DropdownMenuTrigger>
                                  <DropdownMenuContent align="start" className="w-48">
                                    {ACTIVITY_GROUPS.map(group => (
                                      <DropdownMenuSub key={group.label}>
                                        <DropdownMenuSubTrigger className="flex items-center gap-2 text-xs">
                                          <span>{group.groupEmoji}</span><span>{group.label}</span>
                                        </DropdownMenuSubTrigger>
                                        <DropdownMenuSubContent>
                                          {group.activities.map(act => (
                                            <DropdownMenuItem key={act.name} className="text-xs" onClick={() => {
                                              setBoxes(prev => prev.map(b => b.id === box.id ? { ...b, method: act.name } : b));
                                            }}>
                                              {act.name}
                                            </DropdownMenuItem>
                                          ))}
                                        </DropdownMenuSubContent>
                                      </DropdownMenuSub>
                                    ))}
                                  </DropdownMenuContent>
                                </DropdownMenu>
                                
                                {boxes.length > 1 && (
                                  <Button 
                                    variant="ghost" 
                                    size="icon" 
                                    className="h-6 w-6 text-muted-foreground hover:text-destructive hover:bg-destructive/10"
                                    onClick={() => setBoxes(prev => prev.filter(b => b.id !== box.id))}
                                  >
                                    <Trash2 className="h-3.5 w-3.5" />
                                  </Button>
                                )}
                              </div>
                              <div className="p-3 min-h-[60px] flex flex-col gap-2">
                                {box.lgIds.length === 0 ? (
                                  <div className="text-xs text-muted-foreground/40 italic text-center my-auto pointer-events-none">Drop goals here</div>
                                ) : (
                                  box.lgIds.map(lgId => {
                                    const g = validGoals.find(vg => vg.id === lgId);
                                    if (!g) return null;
                                    return (
                                      <div key={g.id} className="text-xs font-body leading-snug p-2.5 bg-background border rounded flex items-start gap-2 group">
                                        <span className="font-mono font-bold opacity-50 shrink-0">LG{validGoals.findIndex(vg => vg.id === g.id) + 1}</span>
                                        <span className="flex-1">{g.goal}</span>
                                        <Button 
                                          variant="ghost" 
                                          size="icon" 
                                          className="h-5 w-5 shrink-0 text-muted-foreground/40 hover:text-destructive opacity-0 group-hover:opacity-100 transition-opacity"
                                          onClick={() => setBoxes(prev => prev.map(b => b.id === box.id ? { ...b, lgIds: b.lgIds.filter(id => id !== lgId) } : b))}
                                        >
                                          <X className="h-3 w-3" />
                                        </Button>
                                      </div>
                                    );
                                  })
                                )}
                              </div>
                            </div>
                          ))}
                        </div>
                      </div>
                      
                      <div className="flex justify-end pt-4 border-t mt-4">
                        <Button
                          onClick={() => {
                            if (boxes.every(b => b.lgIds.length === 0)) {
                              if (!window.confirm("You haven't assigned any goals. They will be ignored. Continue?")) return;
                            }
                            onConfirmEvaluateMappings?.(boxes.map(b => ({ method: b.method, lgIds: b.lgIds })));
                            if (isEditMode) onToggleEditMode?.();
                          }}
                          disabled={isRegenerating}
                          className="bg-primary text-primary-foreground font-semibold px-6"
                          size="sm"
                        >
                          {isRegenerating ? <><Loader2 className="h-3 w-3 mr-2 animate-spin" /> Generating...</> : "Done"}
                        </Button>
                      </div>
                    </div>
                  );
                }

                return (
                  <div className="pl-4 space-y-2">
                    <p className="text-[10px] font-mono uppercase tracking-widest text-muted-foreground mb-2">
                      Understanding Check
                    </p>
                    {allSteps.map((s: string, i: number) => {
                      const noTime = s.replace(/^\d+\s*(?:min|m)[\s—:-]*/i, "").trim();
                      const lgMatch = noTime.match(/^(?:Prompt\s+LG(\d+)|(Combined))\s*[·•\-]\s*\[?([^\]:]+)\]?:\s*(.*)/i);
                      const lgFallback = !lgMatch ? noTime.match(/^Prompt\s+LG(\d+)[:\s]+(.*)/i) : null;
                      const lgNum = lgMatch ? lgMatch[1] : lgFallback ? lgFallback[1] : null;
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

              // Fallback generic renderer for BUFFER, BREAK, SETUP
              return (
                <div className="space-y-4">
                  {(block.sections || []).map((section, sIdx) => {
                    const sectionMode = getSectionMode(section.title || "", mode);
                    const secColors = MODE_COLORS[sectionMode];
                    return (
                      <div key={sIdx} className="relative pl-6">
                        <div className="absolute left-0 top-0 bottom-0 rounded-full" style={{ width: 2, backgroundColor: secColors.border, opacity: 0.25 }} />
                        <div className="space-y-1.5">
                          {(section.steps || []).map((step, stIdx) => {
                            const match = step.match(/^(\d+)\s*(?:min|m)(?:utes?)?\s*(?:—|-|–|:)?\s*(.*)/i);
                            const timeVal = match ? match[1] : "";
                            const contentText = match ? match[2] : step;
                            const subEmoji = getStepEmoji(contentText);
                            const stepMode = getStepMode(contentText, sectionMode);
                            const stepColors = MODE_COLORS[stepMode];

                            return (
                              <div key={stIdx} className={`flex items-start gap-3 group px-3 py-2 ${contentText.toLowerCase().startsWith('activity') ? 'rounded-3xl' : 'rounded-lg'}`}
                                style={{ backgroundColor: 'var(--hestia-surface)', borderWidth: '1px', borderStyle: contentText.toLowerCase().startsWith('prompt') ? 'dashed' : 'solid', borderColor: 'color-mix(in srgb, var(--hestia-text) 10%, transparent)', borderLeftWidth: '2px', borderLeftColor: stepColors.border }}>
                                <div className="w-[42px] shrink-0 flex items-center justify-start opacity-80 group-hover:opacity-100 transition-opacity mt-0.5">
                                  <span className="w-6 text-right text-[11px] font-mono">{timeVal || "0"}</span>
                                  <span className="text-[10px] text-muted-foreground font-mono ml-0.5">m</span>
                                </div>
                                <span className="text-sm mt-0.5 shrink-0" title="Activity Type">{subEmoji}</span>
                                <div className="flex items-center flex-1 min-w-0">
                                  {renderEditable(step, contentText, "flex-1 text-sm text-foreground leading-relaxed py-0.5")}
                                </div>
                                <div className="flex items-center gap-1 shrink-0 opacity-80 group-hover:opacity-100 transition-opacity">
                                  {isEditMode && (
                                    <Button variant="ghost" size="icon" className="h-5 w-5 ml-1 text-muted-foreground hover:text-destructive opacity-0 group-hover:opacity-100 transition-opacity" onClick={() => onDeleteStep(sIdx, stIdx)}>
                                      <Trash2 className="h-3 w-3" />
                                    </Button>
                                  )}
                                </div>
                              </div>
                            );
                          })}
                        </div>
                      </div>
                    );
                  })}
                  {isEditMode && (
                    <div className="flex items-center gap-3 pl-2 mt-2 group">
                      <Plus className="h-3.5 w-3.5 text-muted-foreground group-focus-within:text-primary transition-colors" />
                      <input placeholder="Add detailed step..." className="flex-1 text-sm bg-transparent border-b border-transparent focus:border-primary/50 focus:outline-none py-0.5 text-foreground/85 placeholder:text-muted-foreground/50 transition-colors"
                        onKeyDown={e => {
                          if (e.key === "Enter" && e.currentTarget.value.trim()) {
                            onAddStep(e.currentTarget.value.trim());
                            e.currentTarget.value = "";
                          }
                        }} />
                    </div>
                  )}
                </div>
              );
            })()}
          </CollapsibleContent>
        </div>
      </Collapsible>
    </div>
  );
}
