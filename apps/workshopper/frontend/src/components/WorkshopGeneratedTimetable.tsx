import React, { useState, useRef, useEffect, useCallback } from "react";
import {
  ActivityBlock,
  ActivitySection,
  LearningGoalPlan,
  WorkshopInput,
  WorkshopSession,
} from "@/lib/workshop-generator";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  ArrowLeft, ArrowRight, GripVertical, ChevronDown, ChevronUp,
  Clock, Trash2, Plus, Loader2,
} from "lucide-react";
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  DndContext, closestCenter, KeyboardSensor, PointerSensor,
  useSensor, useSensors, DragEndEvent,
} from "@dnd-kit/core";
import {
  SortableContext, sortableKeyboardCoordinates, useSortable,
  verticalListSortingStrategy, arrayMove,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { toast } from "@/hooks/use-toast";
import { SortableBlockRow } from "./timetable/SortableBlockRow";
import { InlineEditText } from "./timetable/InlineEditText";

import { phaseEmojis, phaseRowColors, getStepEmoji, getStepColorClass, DEFAULT_ACTIVITIES } from "@/lib/constants";
import { parseStepDuration } from "@/lib/utils";

// ── Editing state ──────────────────────────────────────────────────
export type EditTarget =
  | { type: "sessionTitle" }
  | { type: "title"; blockId: string }
  | { type: "step"; blockId: string; sectionIdx: number; stepIdx: number }
  | { type: "stepTime"; blockId: string; sectionIdx: number; stepIdx: number }
  | { type: "blockDuration"; blockId: string }
  | { type: "sectionDuration"; blockId: string; sectionIdx: number }
  | null;

// ── Internal block with stable dnd ID ─────────────────────────────
export interface DndActivityBlock extends ActivityBlock {
  dndId: string;
  lgIndex?: number;
}

// ── Props ──────────────────────────────────────────────────────────
interface Props {
  session: WorkshopSession;
  goals: LearningGoalPlan[];
  meta: WorkshopInput;
  onBack: () => void;
  onNext: (latestSession: WorkshopSession) => void;
  onSaveSession?: (session: WorkshopSession) => void;
}

// ── Inline editable text component removed and imported ───────────────────────────────────────────

// ── Sortable block row component removed and imported ─────────────────────────────────────────────

// ── Main component ─────────────────────────────────────────────────
export default function WorkshopGeneratedTimetable({ session: initialSession, goals, meta, onBack, onNext, onSaveSession }: Props) {
  const [blocks, setBlocks] = useState<DndActivityBlock[]>(() =>
    (initialSession.blocks || []).map((b, i) => ({
      ...b,
      dndId: b.blockId || `block-${i}`,
      lgIndex: (b as any).lgIndex ?? 0,
    }))
  );
  const [sessionTitle, setSessionTitle] = useState(initialSession.title || "");
  const [expandedBlocks, setExpandedBlocks] = useState<Record<string, boolean>>({});
  const [editing, setEditing] = useState<EditTarget>(null);
  const [regeneratingBlockId, setRegeneratingBlockId] = useState<string | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const toggleExpand = (dndId: string) =>
    setExpandedBlocks(prev => ({ ...prev, [dndId]: !prev[dndId] }));

  const updateBlock = (dndId: string, patch: Partial<DndActivityBlock>) =>
    setBlocks(prev => prev.map(b => b.dndId === dndId ? { ...b, ...patch } : b));

  const buildSession = useCallback((): WorkshopSession => ({
    ...initialSession,
    title: sessionTitle,
    blocks: blocks.map(({ dndId: _dndId, lgIndex: _lgIndex, ...rest }) => rest),
  }), [initialSession, sessionTitle, blocks]);

  // Auto-save changes to the parent (debounced)
  useEffect(() => {
    const timeout = setTimeout(() => {
      onSaveSession?.(buildSession());
    }, 500);
    return () => clearTimeout(timeout);
  }, [buildSession, onSaveSession]);

  const handleSaveTitle = (dndId: string, v: string) => {
    updateBlock(dndId, { phaseLabel: v });
    setEditing(null);
  };

  const handleSaveStep = (dndId: string, sectionIdx: number, stepIdx: number, v: string) => {
    setBlocks(prev => prev.map(b => {
      if (b.dndId !== dndId || !b.sections) return b;
      const sections = b.sections.map((sec, si) => {
        if (si !== sectionIdx) return sec;
        const steps = (sec.steps || []).map((st, ti) => {
          if (ti !== stepIdx) return st;
          const match = st.match(/^(\d+)\s*(?:min|m)(?:utes?)?\s*(?:—|-|–|:)?\s*(.*)/i);
          const timePrefix = match ? `${match[1]} min - ` : "";
          return timePrefix + v;
        });
        return { ...sec, steps };
      });
      return { ...b, sections };
    }));
    setEditing(null);
  };

  const handleSaveBlockDuration = (dndId: string, v: string) => {
    let num = parseInt(v, 10);
    if (isNaN(num) || num < 0) num = 0;
    updateBlock(dndId, { duration: num });
    setEditing(null);
  };

  const handleSaveSectionDuration = (dndId: string, sectionIdx: number, v: string) => {
    const num = parseInt(v, 10);
    if (!isNaN(num) && num >= 0) {
      setBlocks(prev => prev.map(b => {
        if (b.dndId !== dndId || !b.sections) return b;
        const sections = b.sections.map((sec, si) => si === sectionIdx ? { ...sec, duration: num } : sec);
        const newDuration = sections.reduce((acc, sec) => acc + (sec.duration || 0), 0);
        return { ...b, sections, duration: newDuration };
      }));
    }
    setEditing(null);
  };

  const handleSaveStepTime = (dndId: string, sectionIdx: number, stepIdx: number, newTime: string) => {
    let num = parseInt(newTime, 10);
    if (isNaN(num) || num < 0) num = 0;
    
    setBlocks(prev => prev.map(b => {
      if (b.dndId !== dndId || !b.sections) return b;
      const sections = b.sections.map((sec, si) => {
        let steps = sec.steps || [];
        if (si === sectionIdx) {
          steps = steps.map((st, ti) => {
            if (ti !== stepIdx) return st;
            const match = st.match(/^(\d+)\s*(?:min|m)(?:utes?)?\s*(?:—|-|–|:)?\s*(.*)/i);
            const contentText = match ? match[2] : st;
            return `${num} min - ${contentText}`;
          });
        }
        const newDuration = steps.reduce((acc, st) => acc + parseStepDuration(st), 0);
        const finalDuration = steps.length > 0 ? newDuration : (sec.duration || 0);
        return { ...sec, steps, duration: finalDuration };
      });
      const newBlockDuration = sections.reduce((acc, sec) => acc + (sec.duration || 0), 0);
      return { ...b, sections, duration: newBlockDuration };
    }));
  };

  const handleAddStep = (dndId: string, text: string) => {
    setBlocks(prev => prev.map(b => {
      if (b.dndId !== dndId) return b;
      const sections = b.sections ? [...b.sections] : [];
      if (sections.length === 0) {
        sections.push({ title: "", duration: 5, steps: [`5 min - ${text}`], methods: [], materials: [] });
      } else {
        const lastSection = { ...sections[sections.length - 1] };
        lastSection.steps = [...(lastSection.steps || []), `5 min - ${text}`];
        lastSection.duration = (lastSection.duration || 0) + 5;
        sections[sections.length - 1] = lastSection;
      }
      const newBlockDuration = sections.reduce((acc, sec) => acc + (sec.duration || 0), 0);
      return { ...b, sections, duration: newBlockDuration };
    }));
  };

  const handleDeleteStep = (dndId: string, sectionIdx: number, stepIdx: number) => {
    setBlocks(prev => prev.map(b => {
      if (b.dndId !== dndId || !b.sections) return b;
      const sections = b.sections.map((sec, si) => {
        if (si !== sectionIdx) return sec;
        const steps = (sec.steps || []).filter((_, ti) => ti !== stepIdx);
        const newDuration = steps.reduce((acc, st) => acc + parseStepDuration(st), 0);
        return { ...sec, steps, duration: newDuration };
      });
      const newBlockDuration = sections.reduce((acc, sec) => acc + (sec.duration || 0), 0);
      return { ...b, sections, duration: newBlockDuration };
    }));
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (over && active.id !== over.id) {
      setBlocks(prev => {
        const oldIndex = prev.findIndex(b => b.dndId === active.id);
        const newIndex = prev.findIndex(b => b.dndId === over.id);
        return arrayMove(prev, oldIndex, newIndex);
      });
    }
  };

  const switchActivity = async (dndId: string, newActivity: string) => {
    setRegeneratingBlockId(dndId);
    try {
      const block = blocks.find(b => b.dndId === dndId);
      if (!block) return;
      const lgIndex = block.lgIndex ?? (block.phase === "LEARNING_CYCLE" ? 1 : 0);

      const singleBlockSkeleton = {
        learningGoal: initialSession.learningGoal,
        omittedGoalIndices: [],
        blocks: [{
          phase: block.phase,
          title: block.phaseLabel,
          description: block.objective,
          lgIndex,
          duration: block.duration,
          sections: (block.sections || []).map(s => ({ title: s.title, duration: s.duration || 0 })),
        }],
      };

      const res = await fetch(import.meta.env.BASE_URL + "api/workshop/session", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          goals,
          meta: { ...meta, selectedActivities: [newActivity] },
          skeleton: singleBlockSkeleton,
        }),
      });

      if (!res.ok) throw new Error("Failed to regenerate activity");
      const newSession = await res.json();
      if (newSession.blocks?.[0]) {
        const updatedBlock = newSession.blocks[0];
        updateBlock(dndId, {
          ...updatedBlock,
          dndId,
          lgIndex,
          blockId: block.blockId,
        });
        toast({ title: "Activity updated", description: `Switched to ${newActivity}` });
      }
    } catch (e) {
      toast({ title: "Generation failed", description: String(e), variant: "destructive" });
    } finally {
      setRegeneratingBlockId(null);
    }
  };

  const handleNext = () => {
    const latest = buildSession();
    onSaveSession?.(latest);
    onNext(latest);
  };

  const totalDuration = blocks.reduce((s, b) => s + b.duration, 0);
  const targetDuration = meta.duration || totalDuration;
  const timePercentage = Math.min(100, Math.round((totalDuration / targetDuration) * 100));

  return (
    <div className="space-y-4">
      <Card className="border-border/60 shadow-lg">
        <CardHeader>
          <div className="flex items-start justify-between gap-4">
            <div className="flex-1 min-w-0">
              <InlineEditText
                value={sessionTitle}
                editing={editing?.type === "sessionTitle"}
                onStartEdit={() => setEditing({ type: "sessionTitle" })}
                onSave={(v) => { setSessionTitle(v); setEditing(null); }}
                className="font-display font-bold text-3xl block w-full"
                inputClassName="py-1.5 px-3 w-full"
              />
              <CardDescription className="mt-1">
                Double-click any title, activity, or step to edit it inline. Click an activity badge to switch it.
              </CardDescription>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
            <SortableContext items={blocks.map(b => b.dndId)} strategy={verticalListSortingStrategy}>
              {blocks.map(block => (
                <SortableBlockRow
                  key={block.dndId}
                  block={block}
                  isExpanded={expandedBlocks[block.dndId] ?? false}
                  editing={editing}
                  meta={meta}
                  isRegenerating={regeneratingBlockId === block.dndId}
                  selectedActivities={meta.selectedActivities || []}
                  onToggleExpand={() => toggleExpand(block.dndId)}
                  onEditTitle={() => setEditing({ type: "title", blockId: block.dndId })}
                  onSaveTitle={v => handleSaveTitle(block.dndId, v)}
                  onEditStep={(sIdx, stIdx) => setEditing({ type: "step", blockId: block.dndId, sectionIdx: sIdx, stepIdx: stIdx })}
                  onSaveStep={(sIdx, stIdx, v) => handleSaveStep(block.dndId, sIdx, stIdx, v)}
                  onEditStepTime={(sIdx, stIdx) => setEditing({ type: "stepTime", blockId: block.dndId, sectionIdx: sIdx, stepIdx: stIdx })}
                  onSaveStepTime={(sIdx, stIdx, v) => handleSaveStepTime(block.dndId, sIdx, stIdx, v)}
                  onEditBlockDuration={() => setEditing({ type: "blockDuration", blockId: block.dndId })}
                  onSaveBlockDuration={v => handleSaveBlockDuration(block.dndId, v)}
                  onEditSectionDuration={(sIdx) => setEditing({ type: "sectionDuration", blockId: block.dndId, sectionIdx: sIdx })}
                  onSaveSectionDuration={(sIdx, v) => handleSaveSectionDuration(block.dndId, sIdx, v)}
                  onDeleteBlock={() => setBlocks(prev => prev.filter(b => b.dndId !== block.dndId))}
                  onSwitchActivity={act => switchActivity(block.dndId, act)}
                  onAddStep={text => handleAddStep(block.dndId, text)}
                  onDeleteStep={(sIdx, stIdx) => handleDeleteStep(block.dndId, sIdx, stIdx)}
                />
              ))}
            </SortableContext>
            
            <div className="mt-4 flex justify-center">
              <Button
                variant="outline"
                className="w-full border-dashed border-2 py-6 text-muted-foreground hover:text-foreground hover:border-primary/50"
                onClick={() => {
                  const newId = `custom-${Date.now()}`;
                  setBlocks(prev => [...prev, {
                    dndId: newId,
                    phase: "CUSTOM",
                    phaseLabel: "Custom Block",
                    goalTag: "",
                    objective: "",
                    description: "",
                    methods: [],
                    materials: [],
                    duration: 10,
                  }]);
                  setExpandedBlocks(prev => ({ ...prev, [newId]: true }));
                }}
              >
                <Plus className="mr-2 h-4 w-4" /> Add Custom Block
              </Button>
            </div>
          </DndContext>
        </CardContent>

        {/* Sticky footer */}
        <div className="sticky bottom-4 z-20 mx-4 mb-4 rounded-xl border border-border/80 bg-white/95 dark:bg-zinc-900/95 backdrop-blur-md shadow-2xl p-3 flex items-center justify-between gap-4 transition-all">
          <Button variant="outline" size="sm" onClick={onBack} className="gap-2 font-body shrink-0">
            <ArrowLeft className="h-4 w-4" /> Previous
          </Button>

          <div className="flex-1 flex flex-col gap-1 max-w-sm relative">
            <div className="flex items-center justify-between text-xs font-medium">
              <div className="flex items-center gap-1.5 text-muted-foreground">
                <Clock className={`h-3.5 w-3.5 ${totalDuration > targetDuration ? "text-destructive" : "text-primary"}`} />
                <span className="hidden sm:inline">Time Allocated</span>
              </div>
              {/* G-2: show explicit overtime label so status isn't color-only */}
              {totalDuration > targetDuration ? (
                <span className="text-destructive font-bold flex items-center gap-1">
                  ⚠ {totalDuration} / {targetDuration} min
                  <span className="text-[10px] font-normal opacity-80">(over by {totalDuration - targetDuration} min)</span>
                </span>
              ) : (
                <span className="text-foreground font-bold">
                  {totalDuration} / {targetDuration} min
                </span>
              )}
            </div>
            <div className="h-1.5 w-full bg-muted/60 rounded-full overflow-hidden shadow-inner">
              <div
                className={`h-full transition-all duration-500 ease-out ${totalDuration > targetDuration ? "bg-destructive" : "bg-primary"}`}
                style={{ width: `${timePercentage}%` }}
              />
            </div>
          </div>

          <Button size="sm" onClick={handleNext} className="gap-2 shadow-md hover:shadow-lg transition-shadow shrink-0">
            Preparation <ArrowRight className="h-3.5 w-3.5" />
          </Button>
        </div>
      </Card>
    </div>
  );
}
