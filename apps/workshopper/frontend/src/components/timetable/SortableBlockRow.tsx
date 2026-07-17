import React from "react";
import { Button } from "@/components/ui/button";
import {
  GripVertical, ChevronDown, ChevronUp,
  Trash2, Plus, Loader2, Info,
} from "lucide-react";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger, DropdownMenuSeparator
} from "@/components/ui/dropdown-menu";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { useSortable } from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { InlineEditText } from "./InlineEditText";
import { phaseEmojis, phaseRowColors, getStepEmoji, getStepColorClass, DEFAULT_ACTIVITIES } from "@/lib/constants";
import { EditTarget, DndActivityBlock } from "../WorkshopGeneratedTimetable";
import { WorkshopInput } from "@/lib/workshop-generator";

export function SortableBlockRow({
  block, isExpanded, editing, meta, isRegenerating, selectedActivities,
  onToggleExpand, onEditTitle, onSaveTitle, onEditStep, onSaveStep,
  onEditStepTime, onSaveStepTime,
  onEditBlockDuration, onSaveBlockDuration, onEditSectionDuration, onSaveSectionDuration,
  onDeleteBlock, onSwitchActivity, onDeleteActivity, onAddActivity, onAddStep, onDeleteStep, isEditMode = false,
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

  const colorClass = phaseRowColors[block.phase] || "bg-muted/30 before:bg-transparent";
  const rawMethods = Array.from(new Set([
    ...(block.methods || []),
    ...(block.sections || []).flatMap(s => s.methods || []),
  ])).filter(m => m && !m.toLowerCase().includes("lecture") && !m.toLowerCase().includes("presentation"));

  const allMethods = rawMethods.filter(m => {
    return !rawMethods.some(other => other !== m && other.toLowerCase().includes(m.toLowerCase()));
  });

  const isTitleEditing = editing?.type === "title" && editing.blockId === block.dndId;

  return (
    <div ref={setNodeRef} style={style} className="mb-2">
      <Collapsible open={isExpanded} onOpenChange={onToggleExpand}>
        <div className={`flex flex-col p-3 border border-border/60 rounded-md transition-colors overflow-hidden relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] ${colorClass} ${isDragging ? "shadow-lg ring-2 ring-primary/30" : ""}`}>
          {/* Main row */}
          <div className="flex items-center gap-3">
            {/* Drag handle */}
            <div {...attributes} {...listeners} className="cursor-grab active:cursor-grabbing text-muted-foreground hover:text-foreground shrink-0 touch-none">
              <GripVertical className="h-5 w-5" />
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
                  disabled={!isEditMode}
                />
                {(allMethods.length > 0 || isEditMode) && (
                  <div className="flex items-center gap-1 flex-wrap">
                    {allMethods.length > 0 && <div className="h-3 w-[1px] bg-border/60 mx-0.5 shrink-0" />}
                    {allMethods.map((m, j) => isEditMode ? (
                      <DropdownMenu key={j}>
                        <DropdownMenuTrigger asChild>
                          <div
                            role="button"
                            onClick={e => e.stopPropagation()}
                            onPointerDown={e => e.stopPropagation()}
                            className="inline-flex items-center gap-1 whitespace-nowrap rounded-md border h-6 px-2 text-[10px] font-medium bg-primary/10 text-primary border-primary/20 cursor-pointer hover:bg-primary/20 transition-colors shrink-0"
                            title="Click to switch activity"
                          >
                            {isRegenerating ? <Loader2 className="h-3 w-3 animate-spin" /> : null}
                            {m} <ChevronDown className="h-2.5 w-2.5 opacity-50" />
                          </div>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent onClick={e => e.stopPropagation()}>
                          {Array.from(new Set([...selectedActivities, ...DEFAULT_ACTIVITIES])).map(act => (
                            <DropdownMenuItem key={act} onClick={e => { e.stopPropagation(); onSwitchActivity(act); }}>
                              Switch to {act}
                            </DropdownMenuItem>
                          ))}
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
                      <TooltipProvider key={j}>
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <div className="inline-flex items-center gap-1 whitespace-nowrap rounded-md border h-6 px-2 text-[10px] font-medium bg-primary/10 text-primary border-primary/20 shrink-0 cursor-help transition-colors hover:bg-primary/20">
                              <Info className="h-2.5 w-2.5 opacity-50" />
                              {m}
                            </div>
                          </TooltipTrigger>
                          <TooltipContent side="top">
                            <p className="text-[11px]">Click "Edit" in the actions menu to change or delete this activity.</p>
                          </TooltipContent>
                        </Tooltip>
                      </TooltipProvider>
                    ))}
                    {isEditMode && (
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" className="h-6 w-6 ml-0.5 text-muted-foreground hover:text-primary hover:bg-primary/10 shrink-0" title="Add activity method">
                            <Plus className="h-3.5 w-3.5" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent onClick={e => e.stopPropagation()}>
                          {Array.from(new Set([...selectedActivities, ...DEFAULT_ACTIVITIES])).map(act => (
                            <DropdownMenuItem key={act} onClick={e => { e.stopPropagation(); onAddActivity(act); }}>
                              Add {act}
                            </DropdownMenuItem>
                          ))}
                        </DropdownMenuContent>
                      </DropdownMenu>
                    )}
                  </div>
                )}
              </div>
            </div>

            {/* Duration + expand + delete */}
            <div className="flex items-center gap-1.5 shrink-0">
              {block.sections && block.sections.length > 0 && block.phase !== "BREAK" && block.phase !== "BUFFER" ? (
                <input
                  type="text"
                  readOnly
                  value={block.duration}
                  onClick={() => {
                    if (!isExpanded) onToggleExpand();
                  }}
                  className="w-8 text-right text-xs font-mono border border-border/60 rounded px-1 py-0.5 bg-muted/50 text-muted-foreground cursor-pointer hover:bg-muted focus:outline-none transition-colors"
                  title="Click to expand and edit detailed times"
                />
              ) : (
                <InlineEditText
                  value={block.duration.toString()}
                  editing={editing?.type === "blockDuration" && editing.blockId === block.dndId}
                  alwaysEdit={isEditMode}
                  onStartEdit={onEditBlockDuration}
                  onSave={onSaveBlockDuration}
                  className="w-8 text-right text-xs font-mono"
                  boxStyle
                  disabled={false}
                />
              )}
              <span className="text-[10px] text-muted-foreground font-mono -ml-0.5">m</span>
              {block.phase !== "BREAK" && block.phase !== "BUFFER" ? (
                <CollapsibleTrigger asChild>
                  <Button variant="ghost" size="icon" className="h-8 w-8 ml-1">
                    {isExpanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                  </Button>
                </CollapsibleTrigger>
              ) : (
                <div className="h-8 w-8 ml-1" />
              )}
              {isEditMode && (
                <Button
                  variant="ghost" size="icon"
                  className="h-7 w-7 text-muted-foreground/50 hover:text-destructive hover:bg-destructive/10 transition-colors shrink-0"
                  onClick={onDeleteBlock}
                  title="Delete block"
                >
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              )}
            </div>
          </div>

          {/* Expanded: sections and steps */}
          <CollapsibleContent className="mt-3 pl-10 pr-2 pb-2 space-y-3">
            {(block.sections || []).map((section, sIdx) => (
                <div key={sIdx}>
                  {block.phase === "LEARNING_CYCLE" && (
                    <div className="flex items-center gap-2 mb-1.5">
                      <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">{section.title}</span>
                    </div>
                  )}
                  <div className="space-y-1">
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
                      const subColorClass = getStepColorClass(contentText);

                      return (
                        <div key={stIdx} className={`flex items-start gap-2 group px-2 py-1.5 rounded-lg border ${subColorClass} shadow-sm`}>
                          <span className="text-sm mt-0.5 opacity-80" title="Activity Type">{subEmoji}</span>
                          <div className="flex items-center flex-1 min-w-0">
                            <InlineEditText
                              value={contentText}
                              editing={isStepEditing}
                              alwaysEdit={isEditMode}
                              multiline={true}
                              onStartEdit={() => onEditStep(sIdx, stIdx)}
                              onSave={v => onSaveStep(sIdx, stIdx, v)}
                              className="flex-1 text-sm text-foreground/85 leading-relaxed py-0.5"
                              disabled={!isEditMode}
                            />
                          </div>
                          <div className="flex items-center gap-1 shrink-0 opacity-80 group-hover:opacity-100 transition-opacity">
                            <InlineEditText
                              value={timeVal || "0"}
                              editing={isStepTimeEditing}
                              alwaysEdit={isEditMode}
                              onStartEdit={() => onEditStepTime(sIdx, stIdx)}
                              onSave={v => onSaveStepTime(sIdx, stIdx, v)}
                              className="w-8 text-right text-xs font-mono"
                              boxStyle
                              disabled={false}
                            />
                            <span className="text-[10px] text-muted-foreground font-mono">m</span>
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
              ))}
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
