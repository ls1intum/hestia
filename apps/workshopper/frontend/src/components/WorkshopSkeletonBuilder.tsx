import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { LearningGoalPlan, SkeletonBlock, SessionSkeleton } from "@/lib/workshop-generator";
import { ArrowLeft, Sparkles, GripVertical, ChevronDown, ChevronUp, Clock, Trash2 } from "lucide-react";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
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
  ARRIVE: "bg-blue-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-blue-500/50 overflow-hidden",
  ACTIVATE: "bg-amber-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-amber-500/40 overflow-hidden",
  INFORM: "bg-indigo-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-indigo-500/40 overflow-hidden",
  PROCESS: "bg-amber-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-amber-500/40 overflow-hidden",
  BREAK: "bg-muted/10 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-muted-foreground/30 overflow-hidden",
  EVALUATE: "bg-purple-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-purple-500/40 overflow-hidden",
  SUMMARY: "bg-emerald-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-emerald-500/40 overflow-hidden",
  LEARNING_CYCLE: "bg-indigo-500/5 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-indigo-500/40 overflow-hidden",
};

interface Props {
  goals: LearningGoalPlan[];
  totalDuration: number;
  initialSkeleton?: SessionSkeleton;
  onBack: () => void;
  onContinue: (skeleton: SessionSkeleton, goals: LearningGoalPlan[]) => void;
  isLoading?: boolean;
}

// ── Internal block type adds a unique string id for DnD ───────────
interface DndBlock extends SkeletonBlock {
  dndId: string;
}

// ── Sortable row ──────────────────────────────────────────────────
interface SortableBlockRowProps {
  block: DndBlock;
  blockIndex: number;
  totalBlocks: number;
  expandedBlocks: Record<string, boolean>;
  isLoading?: boolean;
  onToggleExpand: (dndId: string) => void;
  onUpdateDuration: (dndId: string, duration: number) => void;
  onUpdateSectionDuration: (dndId: string, sectionIndex: number, duration: number) => void;
  onDelete: (dndId: string) => void;
}

function SortableBlockRow({
  block, expandedBlocks, isLoading,
  onToggleExpand, onUpdateDuration, onUpdateSectionDuration, onDelete,
}: SortableBlockRowProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: block.dndId });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.45 : 1,
    zIndex: isDragging ? 10 : undefined,
  };

  const isLC = block.phase === "LEARNING_CYCLE";
  const isExpanded = expandedBlocks[block.dndId] ?? false;

  return (
    <div ref={setNodeRef} style={style} className="mb-2">
      <Collapsible open={isExpanded} onOpenChange={() => isLC && onToggleExpand(block.dndId)}>
        <div className={`flex flex-col p-3 border border-border/60 rounded-md transition-colors ${
          isDragging ? "shadow-lg ring-2 ring-primary/30 z-10" : ""
        } ${phaseRowColors[block.phase] || "relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-transparent bg-muted/30"}`}>
          <div className="flex items-center gap-3">
            {/* Drag handle */}
            <div
              {...attributes}
              {...listeners}
              className="cursor-grab active:cursor-grabbing text-muted-foreground hover:text-foreground transition-colors shrink-0 touch-none"
            >
              <GripVertical className="h-5 w-5" />
            </div>

            {/* Title & phase badge */}
            <div className="flex-1 min-w-0">
              <div className="flex items-start gap-2 mb-1">
                <span className="text-base shrink-0">{phaseEmojis[block.phase] || "✨"}</span>
                <span className="font-body font-semibold text-sm whitespace-normal break-words leading-snug">
                  {block.title}
                </span>
              </div>
              <span className="text-xs text-muted-foreground font-mono bg-muted px-1.5 py-0.5 rounded">{block.phase}</span>
            </div>

            {/* Duration + expand toggle */}
            <div className="flex items-center gap-2 shrink-0">
              <Input
                type="number"
                min={0}
                className={`w-20 text-right h-8 ${isLC ? "bg-muted/50 text-muted-foreground cursor-pointer" : ""}`}
                value={block.duration}
                onChange={(e) => onUpdateDuration(block.dndId, Math.max(0, parseInt(e.target.value) || 0))}
                disabled={isLoading && !isLC}
                readOnly={isLC}
                onClick={(e) => {
                  if (isLC) {
                    e.preventDefault();
                    onToggleExpand(block.dndId);
                  }
                }}
                title={isLC ? "Click to edit section times" : ""}
              />
              <span className="text-sm text-muted-foreground">min</span>
              {isLC && (
                <CollapsibleTrigger asChild>
                  <Button variant="ghost" size="icon" className="h-8 w-8">
                    {isExpanded ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                  </Button>
                </CollapsibleTrigger>
              )}
              <Button
                variant="ghost"
                size="icon"
                className="h-7 w-7 text-muted-foreground/50 hover:text-destructive hover:bg-destructive/10 transition-colors shrink-0"
                onClick={() => onDelete(block.dndId)}
                title="Delete block"
                disabled={isLoading}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
            </div>
          </div>

          {/* Expandable sections */}
          {isLC && block.sections && (
            <CollapsibleContent className="mt-4 pl-10 pr-4 space-y-3 pb-2">
              {block.sections.map((section, sIdx) => (
                <div key={sIdx} className="flex items-center justify-between gap-4">
                  <span className="text-sm font-body">{section.title}</span>
                  <div className="flex items-center gap-2">
                    <Input
                      type="number"
                      min={0}
                      className="w-20 text-right h-7 text-sm"
                      value={section.duration}
                      onChange={(e) => onUpdateSectionDuration(block.dndId, sIdx, Math.max(0, parseInt(e.target.value) || 0))}
                      disabled={isLoading}
                    />
                    <span className="text-xs text-muted-foreground">min</span>
                  </div>
                </div>
              ))}
            </CollapsibleContent>
          )}
        </div>
      </Collapsible>
    </div>
  );
}

// ── Main component ────────────────────────────────────────────────
export default function WorkshopSkeletonBuilder({ goals, totalDuration, initialSkeleton, onBack, onContinue, isLoading }: Props) {
  const [blocks, setBlocks] = useState<DndBlock[]>([]);
  const [expandedBlocks, setExpandedBlocks] = useState<Record<string, boolean>>({});

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  useEffect(() => {
    if (initialSkeleton && initialSkeleton.blocks && initialSkeleton.blocks.length > 0) {
      setBlocks(initialSkeleton.blocks.map((b, i) => ({ ...b, dndId: `restored-${i}` })));
      return;
    }

    const globalEvaluateTime = 10;
    const remaining = Math.max(0, totalDuration - 25 - globalEvaluateTime);
    const breakTime = totalDuration > 60 ? 10 : 0;
    const timePerGoal = goals.length > 0
      ? Math.max(10, Math.floor((remaining - breakTime) / goals.length / 5) * 5)
      : 0;

    const initialBlocks: DndBlock[] = [
      { dndId: "arrive",   phase: "ARRIVE",   title: "Arrive & Welcome",  duration: 5,  lgIndex: 0 },
      { dndId: "activate", phase: "ACTIVATE", title: "Activate Knowledge", duration: 10, lgIndex: 0 },
    ];

    goals.forEach((g, i) => {
      const processTime = Math.max(5, timePerGoal - 5);
      const informTime  = 5;

      initialBlocks.push({
        dndId: `lc-${i}`,
        phase: "LEARNING_CYCLE",
        title: `LG${i + 1}: ${g.goal}`,
        duration: timePerGoal,
        lgIndex: i + 1,
        sections: [
          { title: "Instructor Explain",   duration: informTime },
          { title: "Participants Practice",  duration: processTime },
        ],
      });

      if (i === Math.floor(goals.length / 2) - 1 && breakTime > 0) {
        initialBlocks.push({ dndId: `break-${i}`, phase: "BREAK", title: "Break", duration: breakTime, lgIndex: 0 });
      }
    });

    initialBlocks.push({ dndId: "evaluate", phase: "EVALUATE", title: "Check Understanding", duration: globalEvaluateTime, lgIndex: 0 });
    initialBlocks.push({ dndId: "summary", phase: "SUMMARY", title: "Summary & Wrap-up", duration: 10, lgIndex: 0 });
    setBlocks(initialBlocks);
  }, [goals, totalDuration]);

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

  const updateDuration = (dndId: string, duration: number) =>
    setBlocks(prev => prev.map(b => b.dndId === dndId ? { ...b, duration } : b));

  const updateSectionDuration = (dndId: string, sectionIndex: number, duration: number) =>
    setBlocks(prev => prev.map(b => {
      if (b.dndId !== dndId || !b.sections) return b;
      const sections = b.sections.map((s, i) => i === sectionIndex ? { ...s, duration } : s);
      return { ...b, sections, duration: sections.reduce((sum, s) => sum + (Number(s.duration) || 0), 0) };
    }));

  const removeBlock = (dndId: string) =>
    setBlocks(prev => prev.filter(b => b.dndId !== dndId));

  const toggleExpand = (dndId: string) =>
    setExpandedBlocks(prev => ({ ...prev, [dndId]: !prev[dndId] }));

  const currentTotal = blocks.reduce((acc, b) => acc + (Number(b.duration) || 0), 0);
  const isValid = currentTotal <= totalDuration && currentTotal > 0;
  const timePercentage = Math.min(100, Math.max(0, Math.round((currentTotal / totalDuration) * 100)));

  const handleContinue = () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const skeletonBlocks: SkeletonBlock[] = blocks.map(({ dndId: _dndId, ...rest }) => rest);
    const skeleton: SessionSkeleton = {
      learningGoal: "Combined Session Goals",
      blocks: skeletonBlocks,
      omittedGoalIndices: [],
    };
    const newGoals = goals.map((g) => ({ ...g, priority: 0 }));
    onContinue(skeleton, newGoals);
  };

  return (
    <div className="space-y-4">
      <Card className="border-border/60 shadow-lg">
        <CardHeader>
          <CardTitle className="font-display text-3xl">Step 5: Session Timetable</CardTitle>
          <CardDescription>
            Drag cards to reorder. Expand Learning Cycles to edit section times — the total updates automatically.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
            <SortableContext items={blocks.map(b => b.dndId)} strategy={verticalListSortingStrategy}>
              {blocks.map((b, i) => (
                <SortableBlockRow
                  key={b.dndId}
                  block={b}
                  blockIndex={i}
                  totalBlocks={blocks.length}
                  expandedBlocks={expandedBlocks}
                  isLoading={isLoading}
                  onToggleExpand={toggleExpand}
                  onUpdateDuration={updateDuration}
                  onUpdateSectionDuration={updateSectionDuration}
                  onDelete={removeBlock}
                />
              ))}
            </SortableContext>
          </DndContext>

        </CardContent>

        {/* Sticky Footer for Progress and Actions */}
        <div className="sticky bottom-4 z-20 mx-4 mb-4 rounded-xl border border-border/80 bg-white/95 dark:bg-zinc-900/95 backdrop-blur-md shadow-2xl p-3 flex items-center justify-between gap-4 transition-all">
          <Button variant="outline" size="sm" onClick={onBack} className="gap-2 font-body shrink-0" disabled={isLoading}>
            <ArrowLeft className="h-4 w-4" /> Previous
          </Button>

          <div className="flex-1 flex flex-col gap-1 max-w-sm relative">
            <div className="flex items-center justify-between text-xs font-medium">
              <div className="flex items-center gap-1.5 text-muted-foreground">
                <Clock className={`h-3.5 w-3.5 ${currentTotal > totalDuration ? "text-destructive" : "text-primary"}`} />
                <span className="hidden sm:inline">Time Allocated</span>
              </div>
              <span className={currentTotal > totalDuration ? "text-destructive font-bold" : "text-foreground font-bold"}>
                {currentTotal} / {totalDuration} min
              </span>
            </div>
            <div className="h-1.5 w-full bg-muted/60 rounded-full overflow-hidden shadow-inner relative">
              <div 
                className={`h-full transition-all duration-500 ease-out ${currentTotal > totalDuration ? 'bg-destructive' : 'bg-primary'}`} 
                style={{ width: `${timePercentage}%` }} 
              />
            </div>
            {currentTotal > totalDuration && (
              <p className="text-destructive text-[10px] font-medium leading-tight absolute -top-5 left-1/2 -translate-x-1/2 bg-background px-2 py-0.5 rounded shadow-sm border border-destructive/20 whitespace-nowrap">
                ⚠ Total exceeds duration
              </p>
            )}
          </div>

          <Button onClick={handleContinue} disabled={isLoading || !isValid} size="sm" className="px-6 gap-2 shadow-md hover:shadow-lg transition-shadow shrink-0">
            {isLoading ? <Sparkles className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
            Generate
          </Button>
        </div>
      </Card>
    </div>
  );
}
