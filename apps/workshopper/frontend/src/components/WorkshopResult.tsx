import React, { useState, createContext, useContext } from "react";
import { saveAs } from "file-saver";
import { ActivityBlock, LearningGoalPlan, WorkshopSession, WorkshopInput } from "@/lib/workshop-generator";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Clock, Target, ArrowLeft, ArrowRight, ChevronDown, Pencil, Check, X, Plus, Trash2, AlertTriangle, Users, BookOpen, GripVertical, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { toast } from "@/hooks/use-toast";
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  DragEndEvent,
} from "@dnd-kit/core";
import {
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
  arrayMove,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";

const phaseColors: Record<string, string> = {
  ARRIVE: "bg-primary/10 text-primary border-primary/20",
  ACTIVATE: "bg-accent/10 text-accent border-accent/20",
  INFORM: "bg-primary/10 text-primary border-primary/20",
  PROCESS: "bg-accent/10 text-accent border-accent/20",
  BREAK: "bg-muted text-muted-foreground border-border",
  EVALUATE: "bg-primary/10 text-primary border-primary/20",
  SUMMARY: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border-emerald-400/30",
};

const phaseEmojis: Record<string, string> = {
  ARRIVE: "👋",
  ACTIVATE: "💡",
  INFORM: "🗣️",
  PROCESS: "🛠️",
  BREAK: "☕",
  EVALUATE: "✅",
  SUMMARY: "🏁",
  LEARNING_CYCLE: "🎯",
};

const phaseRowColors: Record<string, string> = {
  ARRIVE: "bg-blue-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-blue-500/50",
  ACTIVATE: "bg-amber-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-amber-500/40",
  INFORM: "bg-indigo-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-indigo-500/40",
  PROCESS: "bg-amber-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-amber-500/40",
  BREAK: "bg-muted/10 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-muted-foreground/30",
  EVALUATE: "bg-purple-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-purple-500/40",
  SUMMARY: "bg-emerald-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-emerald-500/40",
  LEARNING_CYCLE: "bg-indigo-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-indigo-500/40",
};

const DEFAULT_ACTIVITIES = [
  "Group Discussion", "Case Study", "Role Play",
  "Hands-on Practice", "Quiz / Polls", "Q&A Session",
  "Peer Review", "Brainstorming", "Think-Pair-Share"
];

const SortableItemContext = createContext<any>(null);

function SortableAccordionItem({ id, children, value, className }: { id: string; children: React.ReactNode; value: string; className?: string }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id });
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    zIndex: isDragging ? 50 : undefined,
    position: isDragging ? "relative" as const : undefined,
    boxShadow: isDragging ? "0 10px 15px -3px rgb(0 0 0 / 0.1)" : undefined,
  };
  return (
    <SortableItemContext.Provider value={{ attributes, listeners }}>
      <AccordionItem ref={setNodeRef} style={style} value={value} className={`${className || ""} ${isDragging ? "opacity-90 bg-muted/30" : ""}`}>
        {children}
      </AccordionItem>
    </SortableItemContext.Provider>
  );
}

function DragHandle() {
  const { attributes, listeners } = useContext(SortableItemContext) || {};
  if (!attributes) return null;
  return (
    <div
      {...attributes}
      {...listeners}
      className="cursor-grab active:cursor-grabbing text-muted-foreground hover:text-foreground shrink-0 flex items-center justify-center w-5"
      onClick={(e) => e.stopPropagation()}
    >
      <GripVertical className="h-4 w-4" />
    </div>
  );
}

interface Props {
  session: WorkshopSession;
  goals?: LearningGoalPlan[];
  meta: WorkshopInput;
  onBack: () => void;
  onNext: () => void;
  onSaveSession?: (session: WorkshopSession) => void;
}

const fmtTime = (m: number) => {
  const h = Math.floor(m / 60);
  const mm = (m % 60).toString().padStart(2, "0");
  return `${h.toString().padStart(2, "0")}:${mm}`;
};

export default function WorkshopResult({ session: initialSession, goals = [], meta, onBack, onNext, onSaveSession }: Props) {
  const [session, setSession] = useState<WorkshopSession>(() => {
    // Generate stable IDs for blocks if they don't have one
    return {
      ...initialSession,
      blocks: (initialSession.blocks || []).map((b, i) => ({ ...b, blockId: b.blockId || `block-${i}-${Math.random().toString(36).substr(2, 9)}` }))
    };
  });
  const [isEditing, setIsEditing] = useState(false);
  const [isRegeneratingBlock, setIsRegeneratingBlock] = useState<number | null>(null);
  
  const [showEditReminder, setShowEditReminder] = useState(false);
  const [neverShowEditReminder, setNeverShowEditReminder] = useState(() => {
    return localStorage.getItem("workshopper_hide_edit_reminder") === "true";
  });

  const switchActivity = async (blockIndex: number, newActivity: string) => {
    setIsRegeneratingBlock(blockIndex);
    try {
      const block = session.blocks[blockIndex];
      let lgIndex = 1;
      const lgMatch = block.phaseLabel.match(/^LG(\d+)/i);
      if (lgMatch) {
        lgIndex = parseInt(lgMatch[1], 10);
      } else if (block.phase !== "LEARNING_CYCLE") {
        lgIndex = -1;
      }
      
      const singleBlockSkeleton = {
        learningGoal: session.learningGoal,
        omittedGoalIndices: [],
        blocks: [{
          phase: block.phase,
          title: block.phaseLabel,
          description: block.objective,
          lgIndex: lgIndex,
          duration: block.duration,
          sections: block.sections?.map(s => ({ title: s.title, duration: s.duration || 0 })) || []
        }]
      };

      const tempMeta = {
        ...meta,
        selectedActivities: [newActivity]
      };

      const res = await fetch(import.meta.env.BASE_URL + "api/workshop/session", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ goals: goals, meta: tempMeta, skeleton: singleBlockSkeleton })
      });

      if (!res.ok) throw new Error("Failed to regenerate activity");

      const newSession = await res.json();
      if (newSession.blocks && newSession.blocks.length > 0) {
        const updatedBlock = newSession.blocks[0];
        updatedBlock.blockId = block.blockId; // Preserve original drag ID
        updateBlock(blockIndex, updatedBlock);
        toast({ title: "Activity updated", description: `Switched to ${newActivity}` });
      }
    } catch (e) {
      toast({ title: "Generation failed", description: String(e), variant: "destructive" });
    } finally {
      setIsRegeneratingBlock(null);
    }
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (over && active.id !== over.id) {
      setSession(prev => {
        const oldIndex = prev.blocks.findIndex(b => b.blockId === active.id);
        const newIndex = prev.blocks.findIndex(b => b.blockId === over.id);
        return { ...prev, blocks: arrayMove(prev.blocks, oldIndex, newIndex) };
      });
    }
  };

  const totalDuration = session.blocks.reduce((s, b) => s + b.duration, 0);

  // Compute running start times
  let cursor = 0;
  const rows = session.blocks.map((block) => {
    const start = cursor;
    cursor += block.duration;
    return { block, start: fmtTime(start), end: fmtTime(cursor) };
  });

  const updateBlock = (i: number, patch: Partial<ActivityBlock>) => {
    setSession((s) => ({
      ...s,
      blocks: s.blocks.map((b, idx) => (idx === i ? { ...b, ...patch } : b)),
    }));
  };

  const removeBlock = (i: number) => {
    setSession((s) => ({ ...s, blocks: s.blocks.filter((_, idx) => idx !== i) }));
  };

  const updateFlattenedStep = (blockIndex: number, flatStepIndex: number, newTime: string, newContent: string) => {
    setSession(s => {
      const newBlocks = [...s.blocks];
      const block = { ...newBlocks[blockIndex] };
      if (!block.sections) return s;
      
      const newSections = block.sections.map(sec => ({...sec, steps: [...(sec.steps||[])]}));
      let currentFlat = 0;
      let found = false;
      
      for (const sec of newSections) {
        if (!sec.steps) continue;
        if (currentFlat + sec.steps.length > flatStepIndex) {
          const localIndex = flatStepIndex - currentFlat;
          sec.steps[localIndex] = newTime ? `${newTime} min — ${newContent}` : newContent;
          
          sec.duration = sec.steps.reduce((acc, st) => {
            const m = st.match(/^(\d+)\s*(?:min|m)(?:utes?)?\s*(?:—|-|:)/i);
            return acc + (m ? parseInt(m[1], 10) : 0);
          }, 0);
          
          found = true;
          break;
        }
        currentFlat += sec.steps.length;
      }
      
      if (found) {
        block.duration = newSections.reduce((acc, sec) => acc + (sec.duration || 0), 0);
        block.sections = newSections;
        newBlocks[blockIndex] = block;
        return { ...s, blocks: newBlocks };
      }
      return s;
    });
  };

  const deleteFlattenedStep = (blockIndex: number, flatStepIndex: number) => {
    setSession(s => {
      const newBlocks = [...s.blocks];
      const block = { ...newBlocks[blockIndex] };
      if (!block.sections) return s;
      
      const newSections = block.sections.map(sec => ({...sec, steps: [...(sec.steps||[])]}));
      let currentFlat = 0;
      let found = false;
      
      for (const sec of newSections) {
        if (!sec.steps) continue;
        if (currentFlat + sec.steps.length > flatStepIndex) {
          const localIndex = flatStepIndex - currentFlat;
          sec.steps.splice(localIndex, 1);
          
          sec.duration = sec.steps.reduce((acc, st) => {
            const m = st.match(/^(\d+)\s*(?:min|m)(?:utes?)?\s*(?:—|-|:)/i);
            return acc + (m ? parseInt(m[1], 10) : 0);
          }, 0);
          
          found = true;
          break;
        }
        currentFlat += sec.steps.length;
      }
      
      if (found) {
        block.duration = newSections.reduce((acc, sec) => acc + (sec.duration || 0), 0);
        block.sections = newSections;
        newBlocks[blockIndex] = block;
        return { ...s, blocks: newBlocks };
      }
      return s;
    });
  };

  return (
    <div className="space-y-6 pb-20">
      <Card className="border-border/60 shadow-lg bg-white dark:bg-zinc-900 flex flex-col max-w-5xl mx-auto w-full">
        {/* Header Block */}
        <div className="p-6 pb-2">
          <div className="font-display text-2xl mb-2 font-semibold flex items-center justify-between gap-4">
            {isEditing ? (
              <Input
                value={session.title ?? ""}
                onChange={(e) => setSession({ ...session, title: e.target.value })}
                className="text-2xl font-bold font-display h-auto py-1 max-w-md"
              />
            ) : (
              <span>{session.title || "Workshop Session Plan"}</span>
            )}
          </div>
          <div className="flex items-center gap-x-6 gap-y-2 flex-wrap text-sm text-muted-foreground font-body">
            <span className="flex items-center gap-1.5 font-medium"><Clock className="h-4 w-4 text-primary/60" /> {meta.duration || totalDuration} min</span>
            <span className="flex items-center gap-1.5 font-medium"><Users className="h-4 w-4 text-primary/60" /> {meta.participants} participants{session.studentBackground ? `, ${session.studentBackground}` : ""}</span>
          </div>

          <div className="mt-5 space-y-4">
            <div>
              <h3 className="font-body font-semibold flex items-center gap-2 mb-1"><Target className="h-4 w-4 text-primary" /> Learning Goals</h3>
              <ul className="text-sm font-body text-foreground/80 flex flex-col gap-1.5 list-none">
                {goals && goals.length > 0 ? goals.map((g, idx) => (
                  <li key={idx} className="flex items-start gap-1.5">
                    <span className="text-primary/60 mt-0.5">•</span>
                    <span className="leading-snug">{g.goal}</span>
                  </li>
                )) : (
                  <li className="flex items-start gap-1.5">
                    <span className="text-primary/60 mt-0.5">•</span>
                    <span className="leading-snug">{session.learningGoal}</span>
                  </li>
                )}
              </ul>
            </div>

            {session.prerequisites && (
              <div>
                <h3 className="font-body font-semibold flex items-center gap-2 mb-1"><Target className="h-4 w-4 text-primary" /> Prerequisites</h3>
                <ul className="text-sm font-body text-foreground/80 flex flex-wrap gap-x-4 gap-y-1.5 list-none">
                  {session.prerequisites?.split(';').filter(p => p.trim() !== '').map((prereq, idx) => (
                    <li key={idx} className="flex items-start gap-1.5 max-w-full">
                      <span className="text-primary/60 mt-0.5">•</span>
                      <span className="leading-snug text-muted-foreground">{prereq.trim()}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        </div>

        {/* Timetable */}
        <div className="flex-1 px-2 sm:px-12 py-6 pb-8">
          <div className="mx-auto max-w-3xl">

            <DndContext
              sensors={useSensors(
                useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
                useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
              )}
              collisionDetection={closestCenter}
              onDragEnd={handleDragEnd}
            >
              <SortableContext items={session.blocks.map(b => b.blockId as string)} strategy={verticalListSortingStrategy}>
                <div className="relative mt-2">
                  {/* Main Timeline track */}
                  <div className="absolute top-8 bottom-8 left-[64px] w-[2px] bg-border/60 z-0 hidden sm:block" />

                  <Accordion type="multiple" className="space-y-4">
                    {rows.map(({ block, start, end }, i) => (
                      <SortableAccordionItem key={block.blockId || i} id={block.blockId as string} value={`item-${block.blockId || i}`} className={`border-0 relative bg-transparent`}>
                        <AccordionTrigger className="px-4 py-1 hover:no-underline [&>svg]:hidden group">
                          <div className="relative flex items-start sm:items-center gap-4 w-full text-left">
                            
                            {/* Time block */}
                            <div className="w-12 shrink-0 flex flex-col justify-center mt-2 sm:mt-0 relative z-10 text-right hidden sm:flex">
                              <span className="text-xs font-bold text-foreground/80 py-1 pr-2">{block.duration}m</span>
                            </div>

                            {/* Main Timeline dot */}
                            <div className="absolute left-[60px] top-1/2 -translate-y-1/2 w-2.5 h-2.5 rounded-full bg-card border-[2.5px] border-primary/50 shadow-sm z-10 ring-4 ring-background group-hover:border-primary transition-colors hidden sm:block" />

                            {/* Content box */}
                            <div className={`flex-1 border border-border/60 group-hover:border-primary/40 transition-all rounded-xl p-3 sm:py-4 shadow-sm flex items-center gap-3 overflow-hidden min-w-0 ${phaseRowColors[block.phase] || 'bg-white dark:bg-zinc-900'}`}>
                              {isEditing && <DragHandle />}
                              <div className="flex items-center gap-3 overflow-hidden min-w-0 flex-1">
                                <div className="sm:hidden flex flex-col items-start shrink-0 mr-2 border-r border-border/50 pr-3">
                                  <span className="text-xs font-bold text-foreground/80">{block.duration}m</span>
                                </div>
                                <span className="font-body text-sm font-medium text-foreground whitespace-nowrap shrink-0 flex items-center gap-1.5">
                                  <span className="text-base">{phaseEmojis[block.phase] || "✨"}</span>
                                  {block.phaseLabel}
                                </span>
                            {(() => {
                              const allMethods = Array.from(new Set([
                                ...(block.methods || []),
                                ...(block.sections || []).flatMap(s => s.methods || [])
                              ])).filter(m => m && !m.toLowerCase().includes("lecture") && !m.toLowerCase().includes("presentation"));

                              if (allMethods.length === 0) return null;

                              return (
                                <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar">
                                  <div className="h-3 w-[1px] bg-border/60 mx-1 shrink-0"></div>
                                  {allMethods.map((m, j) => (
                                    isEditing ? (
                                      <DropdownMenu key={`meth-${j}`}>
                                        <DropdownMenuTrigger asChild>
                                          <div role="button" onClick={(e) => e.stopPropagation()} onPointerDown={(e) => e.stopPropagation()} className="inline-flex items-center justify-center whitespace-nowrap rounded-md border h-6 px-2 text-[10px] font-medium bg-primary/10 text-primary border-primary/20 cursor-pointer hover:bg-primary/20 transition-colors shrink-0">
                                            {isRegeneratingBlock === i ? <Loader2 className="h-3 w-3 animate-spin mr-1" /> : null}
                                            {m} <ChevronDown className="h-3 w-3 ml-1 opacity-50" />
                                          </div>
                                        </DropdownMenuTrigger>
                                        <DropdownMenuContent onClick={(e) => e.stopPropagation()}>
                                          {Array.from(new Set([...(meta.selectedActivities || []), ...DEFAULT_ACTIVITIES])).map(act => (
                                            <DropdownMenuItem key={act} onClick={(e) => { e.stopPropagation(); switchActivity(i, act); }}>
                                              Switch to {act}
                                            </DropdownMenuItem>
                                          ))}
                                        </DropdownMenuContent>
                                      </DropdownMenu>
                                    ) : (
                                      <div 
                                        key={`meth-${j}`} 
                                        onClick={(e) => {
                                          e.stopPropagation();
                                          toast({
                                            title: "Want to edit this activity?",
                                            description: "Click 'Edit session' below to modify activities, times, and content.",
                                          });
                                        }}
                                        className="inline-flex items-center justify-center whitespace-nowrap rounded-md border h-6 px-2 text-[10px] font-medium bg-background/50 text-foreground/80 border-border/50 cursor-pointer hover:bg-primary/10 hover:text-primary transition-colors shrink-0 backdrop-blur-sm"
                                      >
                                        {m}
                                      </div>
                                    )
                                  ))}
                                </div>
                              );
                            })()}
                              </div>
                              <ChevronDown className="h-4 w-4 text-muted-foreground transition-transform duration-200 shrink-0 ml-auto" />
                            </div>
                          </div>
                        </AccordionTrigger>
                        <AccordionContent className="pl-[20px] sm:pl-[100px] pr-4 pb-4 pt-2">
                          {block.sections && block.sections.length > 0 && (
                            <div className="relative mt-2">
                              {/* Sub Timeline track */}
                              <div className="absolute top-3 bottom-3 left-[43px] w-[2px] bg-border/40" />

                              <div className="space-y-3">
                                  {block.sections.flatMap(s => s.steps || []).map((step, stepIdx) => {
                                    // Parse out duration like "2 min — "
                                    const match = step.match(/^(\d+)\s*(?:min|m)(?:utes?)?\s*(?:—|-|:)\s*(.*)/i);
                                    const timeVal = match ? match[1] : "";
                                    let contentText = match ? match[2] : step;

                                    let subEmoji = "";
                                    let subColorClass = "bg-white dark:bg-zinc-900 border-border/60";
                                    
                                    const lowerContent = contentText.toLowerCase();
                                    if (lowerContent.includes("lecture")) {
                                      subEmoji = "🧑‍🏫";
                                      subColorClass = "bg-blue-50/50 dark:bg-blue-950/20 border-blue-200/60 dark:border-blue-800/60";
                                    } else if (lowerContent.includes("prompt")) {
                                      subEmoji = "❓";
                                      subColorClass = "bg-amber-50/50 dark:bg-amber-950/20 border-amber-200/60 dark:border-amber-800/60";
                                    } else if (lowerContent.includes("activity")) {
                                      subEmoji = "🛠️";
                                      subColorClass = "bg-emerald-50/50 dark:bg-emerald-950/20 border-emerald-200/60 dark:border-emerald-800/60";
                                    }

                                    return (
                                      <div key={stepIdx} className="relative flex items-start gap-4 group">
                                        {/* Time block */}
                                        <div className="w-9 shrink-0 flex justify-end mt-1 relative z-10">
                                          {isEditing ? (
                                            <div className="flex flex-col items-end gap-1">
                                              <Input
                                                value={timeVal}
                                                onChange={(e) => updateFlattenedStep(i, stepIdx, e.target.value, contentText)}
                                                className="w-12 h-7 px-2 py-0 text-xs text-right font-mono font-medium"
                                                placeholder="min"
                                              />
                                            </div>
                                          ) : (
                                            timeVal ? (
                                              <span className="text-[11px] font-bold text-primary/80 bg-primary/10 px-1.5 py-0.5 rounded shadow-sm">{timeVal}m</span>
                                            ) : null
                                          )}
                                        </div>

                                        {/* Timeline dot */}
                                        <div className="absolute left-[39px] top-2.5 w-2.5 h-2.5 rounded-full bg-card border-[2.5px] border-primary/50 shadow-sm z-10 ring-2 ring-background group-hover:border-primary transition-colors" />

                                        {/* Content box */}
                                        {isEditing ? (
                                          <div className="flex-1 flex gap-2">
                                            <textarea
                                              value={contentText}
                                              onChange={(e) => updateFlattenedStep(i, stepIdx, timeVal, e.target.value)}
                                              className="flex-1 min-h-[4rem] text-sm bg-background border border-border/60 rounded-lg p-2 focus:outline-none focus:ring-1 focus:ring-primary shadow-sm"
                                            />
                                            <Button variant="ghost" size="icon" onClick={() => deleteFlattenedStep(i, stepIdx)} className="shrink-0 h-8 w-8 text-muted-foreground hover:text-destructive hover:bg-destructive/10">
                                              <Trash2 className="h-4 w-4" />
                                            </Button>
                                          </div>
                                        ) : (
                                          <div className={`border ${subColorClass} group-hover:border-primary/40 group-hover:shadow-md transition-all rounded-lg px-3 py-2.5 text-sm font-body text-foreground/90 leading-snug shadow-sm`}>
                                            {subEmoji && <span className="mr-1.5">{subEmoji}</span>}
                                            {contentText}
                                          </div>
                                        )}
                                      </div>
                                    );
                                  })}
                                </div>
                              </div>
                            )}
                      </AccordionContent>
                    </SortableAccordionItem>
                  ))}
                </Accordion>
                </div>
              </SortableContext>
            </DndContext>
          </div>
        </div>

        {/* Omitted goals warning */}
        {session.omittedGoals && session.omittedGoals.length > 0 && (
          <div className="m-4 rounded-lg border border-amber-400/60 bg-amber-50/60 dark:bg-amber-950/30 dark:border-amber-500/40 px-4 py-3 space-y-2">
            <div className="flex items-start gap-2">
              <AlertTriangle className="h-4 w-4 text-amber-500 mt-0.5 shrink-0" />
              <div>
                <p className="text-sm font-body font-semibold text-amber-800 dark:text-amber-300">
                  Not all learning goals could be covered
                </p>
                <p className="text-xs font-body text-amber-700 dark:text-amber-400 mt-0.5">
                  The following goal{session.omittedGoals.length > 1 ? "s were" : " was"} left out because there was not enough time to cover {session.omittedGoals.length > 1 ? "them" : "it"} meaningfully within the session duration. Consider reducing other goals or increasing the session time.
                </p>
              </div>
            </div>
            <ul className="ml-6 space-y-1">
              {session.omittedGoals.map((g, i) => (
                <li key={i} className="text-xs font-body text-amber-800 dark:text-amber-300 flex items-start gap-1.5">
                  <span className="text-amber-500 mt-0.5">•</span>
                  {g}
                </li>
              ))}
            </ul>
          </div>
        )}
        {/* Sticky Footer Bar */}
        <div className="sticky bottom-4 z-30 mx-4 mb-4 rounded-xl border border-border/80 bg-white/95 dark:bg-zinc-900/95 backdrop-blur-md shadow-2xl p-3 flex items-center justify-between gap-4 mt-6 transition-all">
          <Button variant="outline" size="sm" onClick={onBack} className="gap-2 font-body shrink-0">
            <ArrowLeft className="h-4 w-4" /> Previous
          </Button>

          {isEditing && (
            <div className="flex-1 flex flex-col gap-1 max-w-sm relative mx-auto hidden md:flex">
              <div className="flex items-center justify-between text-xs font-medium">
                <div className="flex items-center gap-1.5 text-muted-foreground">
                  <Clock className={`h-3.5 w-3.5 ${totalDuration > (meta.duration || totalDuration) ? "text-destructive" : "text-primary"}`} />
                  <span className="hidden sm:inline">Time Allocated</span>
                </div>
                <span className={totalDuration > (meta.duration || totalDuration) ? "text-destructive font-bold" : "text-foreground font-bold"}>
                  {totalDuration} / {meta.duration || totalDuration} min
                </span>
              </div>
              <div className="h-1.5 w-full bg-muted/60 rounded-full overflow-hidden shadow-inner relative">
                <div 
                  className={`h-full transition-all duration-500 ease-out ${totalDuration > (meta.duration || totalDuration) ? 'bg-destructive' : 'bg-primary'}`} 
                  style={{ width: `${Math.min(100, Math.max(0, Math.round((totalDuration / (meta.duration || totalDuration)) * 100)))}%` }} 
                />
              </div>
              {totalDuration > (meta.duration || totalDuration) && (
                <p className="text-destructive text-[10px] font-medium leading-tight absolute -top-5 left-1/2 -translate-x-1/2 bg-background px-2 py-0.5 rounded shadow-sm border border-destructive/20 whitespace-nowrap">
                  Exceeds total time by {totalDuration - (meta.duration || totalDuration)} min
                </p>
              )}
            </div>
          )}

          <div className="flex items-center gap-2 flex-wrap justify-end">
            {isEditing ? (
              <Button size="sm" variant="default" onClick={() => {
                if (totalDuration > (meta.duration || totalDuration)) {
                  toast({
                    title: "Total time exceeded",
                    description: `The total time (${totalDuration} min) exceeds the planned session duration (${meta.duration} min). Please reduce the duration of some blocks before saving.`,
                    variant: "destructive"
                  });
                  return;
                }
                setIsEditing(false);
                onSaveSession?.(session);
                toast({ title: "Changes saved" });
              }} className="gap-2 shadow-sm">
                <Check className="h-3.5 w-3.5" /> Done editing
              </Button>
            ) : (
              <Button size="sm" variant="outline" onClick={() => {
                if (neverShowEditReminder) {
                  setIsEditing(true);
                } else {
                  setShowEditReminder(true);
                }
              }} className="gap-2">
                <Pencil className="h-3.5 w-3.5" /> Edit session
              </Button>
            )}
            <Button size="sm" onClick={onNext} className="gap-2 shadow-md hover:shadow-lg transition-shadow">
              Continue to Preparation <ArrowRight className="h-3.5 w-3.5" />
            </Button>
          </div>
        </div>
      </Card>

      <Dialog open={showEditReminder} onOpenChange={setShowEditReminder}>
        <DialogContent className="sm:max-w-[425px]">
          <DialogHeader>
            <DialogTitle>Entering Edit Mode</DialogTitle>
            <DialogDescription className="pt-2">
              In edit mode, you can:
              <ul className="list-disc ml-5 mt-2 space-y-1 text-foreground/80">
                <li>Change activity types (e.g., Quiz/Poll to Group Discussion)</li>
                <li>Edit the content of individual event blocks</li>
                <li>Adjust the time for each event block</li>
                <li>Delete event blocks</li>
              </ul>
              <div className="mt-4 p-3 bg-blue-50 dark:bg-blue-950/30 text-blue-800 dark:text-blue-300 rounded-md text-sm border border-blue-100 dark:border-blue-900">
                <strong>Note:</strong> If you need to make structural changes like reordering main phases (Activate, Arrive...) or changing learning goals, please go back to <strong>Step 5 (Timetable)</strong>.
              </div>
            </DialogDescription>
          </DialogHeader>
          <div className="flex items-center space-x-2 py-4">
            <input 
              type="checkbox" 
              id="never-show" 
              className="h-4 w-4 rounded border-border text-primary focus:ring-primary cursor-pointer accent-primary"
              checked={neverShowEditReminder} 
              onChange={(e) => {
                const val = e.target.checked;
                setNeverShowEditReminder(val);
                if (val) localStorage.setItem("workshopper_hide_edit_reminder", "true");
                else localStorage.removeItem("workshopper_hide_edit_reminder");
              }} 
            />
            <label htmlFor="never-show" className="text-sm font-medium leading-none cursor-pointer">
              Don't show this again
            </label>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowEditReminder(false)}>Cancel</Button>
            <Button onClick={() => { setShowEditReminder(false); setIsEditing(true); }}>Enter Edit Mode</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
