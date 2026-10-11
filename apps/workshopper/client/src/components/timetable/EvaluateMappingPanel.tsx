import React, { useState, useEffect } from "react";
import { Plus, ChevronDown, Trash2, X, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, 
  DropdownMenuTrigger, DropdownMenuSub, DropdownMenuSubTrigger, DropdownMenuSubContent
} from "@/components/ui/dropdown-menu";
import { ACTIVITY_GROUPS } from "@/lib/constants";
import { LearningGoalPlan } from "@/lib/workshop-generator";
import { defaultEvaluateMappings } from "@/lib/activity-taxonomy";

interface EvaluateMappingPanelProps {
  initialMappings?: { method: string; lgIds: string[] }[];
  goals: LearningGoalPlan[];
  /** The user's preferred activities; new boxes and the default mapping use these */
  selectedActivities?: string[];
  blockDuration: number;
  isRegenerating: boolean;
  isEditMode: boolean;
  onConfirmEvaluateMappings: (mappings: { method: string; lgIds: string[] }[]) => void;
  onToggleEditMode?: () => void;
  /** Reports the current (unconfirmed) boxes so the block's own Done button can apply them */
  onChange?: (mappings: { method: string; lgIds: string[] }[]) => void;
}

export function EvaluateMappingPanel({
  initialMappings,
  goals,
  selectedActivities = [],
  blockDuration,
  isRegenerating,
  isEditMode,
  onConfirmEvaluateMappings,
  onToggleEditMode,
  onChange
}: EvaluateMappingPanelProps) {
  const validGoals = goals;
  const defaultMethod = selectedActivities[0] || "Quiz / Polls";
  
  const [boxes, setBoxes] = useState<{ id: string; method: string; lgIds: string[] }[]>(() => {
    if (initialMappings && initialMappings.length > 0) {
      return initialMappings.map((m, i) => ({ id: `box-${Date.now()}-${i}`, method: m.method, lgIds: m.lgIds }));
    }
    // No mapping yet: start from each goal's best-fit activity (Bloom's/SOLO), grouped per activity
    const defaults = defaultEvaluateMappings(goals, selectedActivities);
    if (defaults.length > 0) {
      return defaults.map((m, i) => ({ id: `box-${Date.now()}-${i}`, method: m.method, lgIds: m.lgIds }));
    }
    return [{ id: `box-${Date.now()}`, method: defaultMethod, lgIds: [] }];
  });

  useEffect(() => {
    onChange?.(boxes.map(b => ({ method: b.method, lgIds: b.lgIds })));
  }, [boxes]); // eslint-disable-line react-hooks/exhaustive-deps

  const unassignedLgs = validGoals.filter(g => !boxes.some(b => b.lgIds.includes(g.id!)));
  const estimatedTimePerActivity = 5;
  const isOverLimit = boxes.length * estimatedTimePerActivity > blockDuration;

  return (
    <div className="mt-4 p-4 rounded-xl border-2 border-primary/20 bg-primary/5 shadow-inner">
      <div className="flex items-center gap-2 mb-4 text-primary font-bold">
        <div className="h-6 w-6 rounded-full bg-primary text-primary-foreground flex items-center justify-center text-sm font-mono">LG</div>
        Map Learning Goals to Assessments
      </div>
      <p className="text-sm text-muted-foreground mb-6 leading-relaxed">
        Drag your unassigned learning goals into the assessment boxes. You can add multiple boxes to assess different goals using different methods.
      </p>

      <div className="flex gap-4 items-start relative">
        <div className="w-1/3 flex flex-col gap-2 shrink-0">
          <div className="text-xs font-mono font-bold text-muted-foreground uppercase tracking-widest mb-1">Unassigned Goals</div>
          {unassignedLgs.length === 0 ? (
            <div className="text-xs text-muted-foreground/60 italic p-3 border border-dashed rounded-lg text-center">All goals assigned!</div>
          ) : (
            unassignedLgs.map((g) => (
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
        
        <div className="w-px self-stretch bg-border/50 mx-2" />

        <div className="flex-1 flex flex-col gap-3">
          <div className="flex items-center justify-between mb-1">
            <div className="text-xs font-mono font-bold text-muted-foreground uppercase tracking-widest">Assessment Blocks</div>
            <Button 
              variant="outline" 
              size="sm" 
              className="h-7 text-xs px-2.5 font-bold"
              onClick={() => setBoxes(prev => [...prev, { id: `box-${Date.now()}`, method: defaultMethod, lgIds: [] }])}
            >
              <Plus className="h-3 w-3 mr-1" /> Add Box
            </Button>
          </div>
          
          {isOverLimit && (
            <div className="text-xs text-amber-600 bg-amber-500/10 p-2 rounded border border-amber-500/20 leading-tight">
              Warning: {boxes.length} activities might be too much for a {blockDuration}-minute block.
            </div>
          )}

          {boxes.map((box) => (
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
                    const filtered = b.lgIds.filter(id => id !== lgId);
                    if (b.id === box.id) return { ...b, lgIds: [...filtered, lgId] };
                    return { ...b, lgIds: filtered };
                  }));
                }
              }}
            >
              <div className="bg-muted/30 px-3 py-2 flex items-center justify-between border-b">
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <div className="inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-bold uppercase tracking-wider rounded bg-primary/10 text-primary cursor-pointer hover:bg-primary/20 transition-colors">
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
            onConfirmEvaluateMappings(boxes.map(b => ({ method: b.method, lgIds: b.lgIds })));
            if (isEditMode && onToggleEditMode) onToggleEditMode();
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
