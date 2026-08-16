import React from "react";
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
import { phaseEmojis, getStepEmoji, DEFAULT_ACTIVITIES, ACTIVITY_GROUPS, MODE_COLORS, getPhaseMode, getSectionMode, getStepMode } from "@/lib/constants";
import { EditTarget, DndActivityBlock } from "../WorkshopGeneratedTimetable";
import { WorkshopInput } from "@/lib/workshop-generator";

export function SortableBlockRow({
  block, isExpanded, editing, meta, isRegenerating, selectedActivities,
  onToggleExpand, onEditTitle, onSaveTitle, onEditStep, onSaveStep,
  onEditStepTime, onSaveStepTime,
  onEditBlockDuration, onSaveBlockDuration, onEditSectionDuration, onSaveSectionDuration,
  onDeleteBlock, onSwitchActivity, onDeleteActivity, onAddActivity, onAddStep, onDeleteStep,
  isEditMode = false, onToggleEditMode,
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
  onSwitchActivity: (method: string) => void;
  onDeleteActivity: (method: string) => void;
  onAddActivity: (method: string) => void;
  onAddStep: (text: string) => void;
  onDeleteStep: (sectionIdx: number, stepIdx: number) => void;
  isEditMode?: boolean;
  onToggleEditMode?: () => void;
}) {
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
    backgroundColor: modeColors.bgTint,
    border: isBreak ? `1.5px dashed ${modeColors.border}` : `1px solid var(--hestia-border)`,
    borderLeft: isBreak ? `1.5px dashed ${modeColors.border}` : `2px solid ${modeColors.border}`,
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
              </div>
            </div>

            {/* Duration + action icons + expand (rightmost) */}
            <div className="flex items-center gap-1.5 shrink-0">


              {/* Activity tags (Methods) */}
              {(allMethods.length > 0) && (
                <div className="flex items-center gap-1 mr-1">
                  {allMethods.map((m, j) => isEditMode ? (
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
                                  <DropdownMenuItem key={act} onClick={e => { e.stopPropagation(); onSwitchActivity(act); }}>
                                    Switch to {act}
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
                                  <DropdownMenuItem key={act} onClick={e => { e.stopPropagation(); onSwitchActivity(act); }}>
                                    Switch to {act}
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

              {/* Edit icon — pencil to enter, check to exit */}
              <Button
                variant="ghost" size="icon"
                className={`h-7 w-7 transition-colors shrink-0 ${
                  isEditMode
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
          <CollapsibleContent className="mt-4 pb-1 space-y-4 relative z-0">
            {(block.sections || []).map((section, sIdx) => {
              const sectionMode = getSectionMode(section.title || "", mode);
              const secColors = MODE_COLORS[sectionMode];
              return (
                <div key={sIdx} className="relative pl-6">
                  {/* Vertical tree line */}
                  <div 
                    className="absolute left-0 top-0 bottom-0 rounded-full" 
                    style={{ width: 2, backgroundColor: secColors.border, opacity: 0.25 }}
                  />
                  
                  {block.phase === "LEARNING_CYCLE" && section.title && (
                    <div className="mb-2">
                      <span 
                        className="inline-flex font-mono text-[0.75rem] font-semibold uppercase tracking-[0.07em] px-2.5 py-0.5 rounded-md"
                        style={{ backgroundColor: secColors.badgeBg, color: secColors.badgeText }}
                      >
                        {section.title}
                      </span>
                    </div>
                  )}
                  <div className="space-y-1.5">
                    {(section.steps || []).map((step, stIdx) => {
                      const isStepEditing = editing?.type === "step" &&
                        editing.blockId === block.dndId &&
                        editing.sectionIdx === sIdx &&
                        editing.stepIdx === stIdx;
                      const isStepTimeEditing = editing?.type === "stepTime" &&
                        editing.blockId === block.dndId &&
                        editing.sectionIdx === sIdx &&
                        editing.stepIdx === stIdx;
                      const match = step.match(/^(\d+)\s*(?:min|m)(?:utes?)?\s*(?:—|-|–|:)?\s*(.*)/i);
                      const timeVal = match ? match[1] : "";
                      const contentText = match ? match[2] : step;
                      const subEmoji = getStepEmoji(contentText);
                      const stepMode = getStepMode(contentText, sectionMode);
                      const stepColors = MODE_COLORS[stepMode];

                      return (
                        <div 
                          key={stIdx} 
                          className={`flex items-start gap-3 group px-3 py-2 ${contentText.toLowerCase().startsWith('activity') ? 'rounded-3xl' : 'rounded-lg'}`}
                          style={{
                            backgroundColor: 'var(--hestia-surface)',
                            borderWidth: '1px',
                            borderStyle: contentText.toLowerCase().startsWith('prompt') ? 'dashed' : 'solid',
                            borderColor: 'color-mix(in srgb, var(--hestia-text) 10%, transparent)',
                            borderLeftWidth: '2px',
                            borderLeftStyle: (contentText.toLowerCase().startsWith('explain') && section.title?.toLowerCase().includes('practice')) ? 'dotted' : 'solid',
                            borderLeftColor: contentText.toLowerCase().startsWith('prompt') ? 'var(--hestia-phase-evaluate)' : contentText.toLowerCase().startsWith('activity') ? 'var(--hestia-phase-setup)' : stepColors.border,
                          }}
                        >
                          <div className="w-[42px] shrink-0 flex items-center justify-start opacity-80 group-hover:opacity-100 transition-opacity mt-0.5">
                            <InlineEditText
                              value={timeVal || "0"}
                              editing={isStepTimeEditing}
                              alwaysEdit={isEditMode}
                              onStartEdit={() => onEditStepTime(sIdx, stIdx)}
                              onSave={v => onSaveStepTime(sIdx, stIdx, v)}
                              className="w-6 text-right text-[11px] font-mono"
                              boxStyle
                              disabled={false}
                            />
                            <span className="text-[10px] text-muted-foreground font-mono ml-0.5">m</span>
                          </div>
                          <span className="text-sm mt-0.5 shrink-0" title="Activity Type">{subEmoji}</span>
                          <div className="flex items-center flex-1 min-w-0">
                            <InlineEditText
                              value={contentText}
                              editing={isStepEditing}
                              alwaysEdit={isEditMode}
                              multiline={true}
                              onStartEdit={() => onEditStep(sIdx, stIdx)}
                              onSave={v => onSaveStep(sIdx, stIdx, v)}
                              className="flex-1 text-sm text-foreground leading-relaxed py-0.5"
                              disabled={!isEditMode}
                            />
                          </div>
                          <div className="flex items-center gap-1 shrink-0 opacity-80 group-hover:opacity-100 transition-opacity">
                            {isEditMode && (
                              <Button 
                                variant="ghost" size="icon" 
                                className="h-5 w-5 ml-1 text-muted-foreground hover:text-destructive opacity-0 group-hover:opacity-100 transition-opacity" 
                                onClick={() => onDeleteStep(sIdx, stIdx)}
                              >
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
                <input 
                  placeholder="Add detailed step..." 
                  className="flex-1 text-sm bg-transparent border-b border-transparent focus:border-primary/50 focus:outline-none py-0.5 text-foreground/85 placeholder:text-muted-foreground/50 transition-colors"
                  onKeyDown={e => {
                    if (e.key === "Enter" && e.currentTarget.value.trim()) {
                      onAddStep(e.currentTarget.value.trim());
                      e.currentTarget.value = "";
                    }
                  }}
                />
              </div>
            )}
          </CollapsibleContent>
        </div>
      </Collapsible>
    </div>
  );
}
