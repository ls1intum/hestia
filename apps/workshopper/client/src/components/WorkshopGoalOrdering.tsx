import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { LearningGoalPlan } from "@/lib/workshop-generator";
import { ArrowLeft, ArrowRight, GripVertical } from "lucide-react";
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

interface Props {
  initialGoals: LearningGoalPlan[];
  onBack: () => void;
  onContinue: (goals: LearningGoalPlan[]) => void;
  isLoading?: boolean;
}

function SortableGoalCard({ goal, index }: { goal: LearningGoalPlan; index: number }) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: goal.id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`flex items-center gap-3 p-3 border rounded-xl bg-muted/40 shadow-sm select-none ${isDragging ? "shadow-lg ring-2 ring-primary/30 bg-muted/60" : "border-border/60"
        }`}
    >
      <div
        {...attributes}
        {...listeners}
        className="cursor-grab active:cursor-grabbing text-muted-foreground hover:text-foreground transition-colors shrink-0 touch-none"
        aria-label="Drag to reorder"
      >
        <GripVertical className="h-5 w-5" />
      </div>
      <div className="flex-1 font-body text-sm font-medium leading-snug">{goal.goal}</div>
      <div className="shrink-0 text-xs text-muted-foreground font-body bg-muted px-2 py-1 rounded">
        #{index + 1}
      </div>
    </div>
  );
}

export default function WorkshopGoalOrdering({ initialGoals, onBack, onContinue, isLoading }: Props) {
  const [goals, setGoals] = useState<LearningGoalPlan[]>(initialGoals);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates })
  );

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (over && active.id !== over.id) {
      setGoals(prev => {
        const oldIndex = prev.findIndex(g => g.id === active.id);
        const newIndex = prev.findIndex(g => g.id === over.id);
        return arrayMove(prev, oldIndex, newIndex);
      });
    }
  };

  return (
    <div className="space-y-4">
      <Card className="border-border/60 shadow-lg bg-card">
        <CardHeader className="py-4">
          <CardTitle className="font-display text-3xl">Order Learning Goals</CardTitle>
          <CardDescription className="font-body text-sm">
            Drag the cards to set the order in which goals appear in the session.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
            <SortableContext items={goals.map(g => g.id)} strategy={verticalListSortingStrategy}>
              {goals.map((g, i) => (
                <SortableGoalCard key={g.id} goal={g} index={i} />
              ))}
            </SortableContext>
          </DndContext>

          {/* Navigation — bottom */}
          <div className="flex justify-between pt-4">
            <Button variant="outline" onClick={onBack} className="gap-2 font-body" disabled={isLoading}>
              <ArrowLeft className="h-4 w-4" /> Previous step
            </Button>
            <Button onClick={() => onContinue(goals)} disabled={isLoading || goals.length === 0} size="lg" className="px-10 gap-2">
              Next step <ArrowRight className="h-4 w-4" />
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
