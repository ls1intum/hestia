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

// ── Phase styling ──────────────────────────────────────────────────
const phaseEmojis: Record<string, string> = {
  ARRIVE: "👋", ACTIVATE: "💡", INFORM: "🗣️", PROCESS: "🛠️",
  BREAK: "☕", EVALUATE: "✅", SUMMARY: "🏁", LEARNING_CYCLE: "🎯",
  CUSTOM: "✏️", BUFFER: "⏳",
};

const phaseRowColors: Record<string, string> = {
  ARRIVE: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-primary",
  ACTIVATE: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-accent",
  INFORM: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-primary/80",
  PROCESS: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-accent/80",
  BREAK: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-muted-foreground",
  EVALUATE: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-primary/60",
  SUMMARY: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-accent/60",
  LEARNING_CYCLE: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-primary",
  CUSTOM: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-border",
  BUFFER: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-muted",
};

const getStepEmoji = (text: string) => {
  const t = text.toLowerCase();
  if (t.includes("lecture") || t.includes("presentation") || t.includes("explain") || t.includes("concept")) return "🧑‍🏫";
  if (t.includes("prompt") || t.includes("question") || t.includes("q&a") || t.includes("quiz")) return "❓";
  if (t.includes("activity") || t.includes("exercise") || t.includes("practice")) return "🛠️";
  if (t.includes("discuss") || t.includes("debate") || t.includes("share")) return "💬";
  if (t.includes("brainstorm") || t.includes("ideate")) return "💡";
  if (t.includes("review") || t.includes("feedback") || t.includes("evaluate")) return "✅";
  if (t.includes("welcome") || t.includes("intro")) return "👋";
  if (t.includes("wrap") || t.includes("summary") || t.includes("conclusion")) return "🏁";
  if (t.includes("break") || t.includes("pause")) return "☕";
  if (t.includes("read") || t.includes("case study")) return "📖";
  if (t.includes("video") || t.includes("watch")) return "🎥";
  if (t.includes("role play") || t.includes("simulate")) return "🎭";
  if (t.includes("icebreaker") || t.includes("game")) return "🎲";
  return "✨";
};

const getStepColorClass = (text: string) => {
  return "bg-card border-border/60";
};

const DEFAULT_ACTIVITIES = [
  "Group Discussion", "Case Study", "Role Play",
  "Hands-on Practice", "Quiz / Polls", "Q&A Session",
  "Peer Review", "Brainstorming", "Think-Pair-Share",
];

// ── Editing state ──────────────────────────────────────────────────
type EditTarget =
  | { type: "sessionTitle" }
  | { type: "title"; blockId: string }
  | { type: "step"; blockId: string; sectionIdx: number; stepIdx: number }
  | { type: "stepTime"; blockId: string; sectionIdx: number; stepIdx: number }
  | { type: "blockDuration"; blockId: string }
  | { type: "sectionDuration"; blockId: string; sectionIdx: number }
  | null;

// ── Internal block with stable dnd ID ─────────────────────────────
interface DndActivityBlock extends ActivityBlock {
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

// ── Inline editable text ───────────────────────────────────────────
function InlineEditText({
  value, editing, onStartEdit, onSave, multiline = false, className = "", inputClassName = "text-sm px-2 py-0.5", boxStyle = false
}: {
  value: string;
  editing: boolean;
  onStartEdit: () => void;
  onSave: (v: string) => void;
  multiline?: boolean;
  className?: string;
  inputClassName?: string;
  boxStyle?: boolean;
}) {
  const [draft, setDraft] = useState(value);
  const ref = useRef<HTMLInputElement & HTMLTextAreaElement>(null);

  useEffect(() => {
    if (editing) {
      setDraft(value);
      setTimeout(() => ref.current?.focus(), 30);
    }
  }, [editing, value]);

  const commit = () => onSave(draft);

  if (editing) {
    if (multiline) {
      return (
        <textarea
          ref={ref as React.Ref<HTMLTextAreaElement>}
          value={draft}
          onChange={e => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={e => { if (e.key === "Escape") onSave(value); }}
          rows={3}
          className={`w-full text-sm bg-background border border-primary/60 rounded-md p-2 resize-none focus:outline-none focus:ring-1 focus:ring-primary shadow-sm ${className}`}
        />
      );
    }
    return (
      <input
        ref={ref as React.Ref<HTMLInputElement>}
        value={draft}
        onChange={e => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={e => { if (e.key === "Enter") commit(); if (e.key === "Escape") onSave(value); }}
        className={`font-body font-semibold bg-background border border-primary/60 rounded-md focus:outline-none focus:ring-1 focus:ring-primary ${inputClassName} ${className}`}
      />
    );
  }

  return (
    <span
      className={`cursor-text select-text ${boxStyle ? "inline-block border border-border/60 rounded px-1 py-0.5 bg-background/50 hover:bg-background transition-colors" : ""} ${className}`}
      onDoubleClick={e => { e.stopPropagation(); onStartEdit(); }}
      onClick={e => {
        if (boxStyle) {
          e.stopPropagation();
          onStartEdit();
        }
      }}
      title={boxStyle ? "Click to edit" : "Double-click to edit"}
    >
      {value || (boxStyle ? "0" : "Click to edit...")}
    </span>
  );
}

// ── Sortable block row ─────────────────────────────────────────────
function SortableBlockRow({
  block, isExpanded, editing, meta, isRegenerating, selectedActivities,
  onToggleExpand, onEditTitle, onSaveTitle, onEditStep, onSaveStep,
  onEditStepTime, onSaveStepTime,
  onEditBlockDuration, onSaveBlockDuration, onEditSectionDuration, onSaveSectionDuration,
  onDeleteBlock, onSwitchActivity, onAddStep, onDeleteStep,
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
  onAddStep: (text: string) => void;
  onDeleteStep: (sectionIdx: number, stepIdx: number) => void;
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: block.dndId });
  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.45 : 1,
    zIndex: isDragging ? 10 : undefined,
  };

  const colorClass = phaseRowColors[block.phase] || "bg-muted/30 before:bg-transparent";
  const allMethods = Array.from(new Set([
    ...(block.methods || []),
    ...(block.sections || []).flatMap(s => s.methods || []),
  ])).filter(m => m && !m.toLowerCase().includes("lecture") && !m.toLowerCase().includes("presentation"));

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
                  onStartEdit={onEditTitle}
                  onSave={onSaveTitle}
                  className="font-body font-semibold text-sm"
                />
                {allMethods.length > 0 && (
                  <div className="flex items-center gap-1 flex-wrap">
                    <div className="h-3 w-[1px] bg-border/60 mx-0.5 shrink-0" />
                    {allMethods.map((m, j) => (
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
                        </DropdownMenuContent>
                      </DropdownMenu>
                    ))}
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
                  onStartEdit={onEditBlockDuration}
                  onSave={onSaveBlockDuration}
                  className="w-8 text-right text-xs font-mono"
                  boxStyle
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
              <Button
                variant="ghost" size="icon"
                className="h-7 w-7 text-muted-foreground/50 hover:text-destructive hover:bg-destructive/10 transition-colors shrink-0"
                onClick={onDeleteBlock}
                title="Delete block"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
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
                          <InlineEditText
                            value={contentText}
                            editing={isStepEditing}
                            onStartEdit={() => onEditStep(sIdx, stIdx)}
                            onSave={v => onSaveStep(sIdx, stIdx, v)}
                            className="flex-1 text-sm text-foreground/85 leading-snug py-0.5"
                          />
                          <div className="flex items-center gap-1 shrink-0 opacity-80 group-hover:opacity-100 transition-opacity">
                            <InlineEditText
                              value={timeVal || "0"}
                              editing={isStepTimeEditing}
                              onStartEdit={() => onEditStepTime(sIdx, stIdx)}
                              onSave={v => onSaveStepTime(sIdx, stIdx, v)}
                              className="w-8 text-right text-xs font-mono"
                              boxStyle
                            />
                            <span className="text-[10px] text-muted-foreground font-mono">m</span>
                            <Button 
                              variant="ghost" size="icon" 
                              className="h-5 w-5 ml-1 text-muted-foreground hover:text-destructive opacity-0 group-hover:opacity-100 transition-opacity" 
                              onClick={() => onDeleteStep(sIdx, stIdx)}
                            >
                              <Trash2 className="h-3 w-3" />
                            </Button>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              ))}
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
            </CollapsibleContent>
        </div>
      </Collapsible>
    </div>
  );
}

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
        const newDuration = steps.reduce((acc, st) => {
          const m = st.match(/^(\d+)/);
          return acc + (m ? parseInt(m[1], 10) : 0);
        }, 0);
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
        const newDuration = steps.reduce((acc, st) => {
          const m = st.match(/^(\d+)/);
          return acc + (m ? parseInt(m[1], 10) : 0);
        }, 0);
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
