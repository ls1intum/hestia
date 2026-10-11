import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { LearningGoalPlan } from "@/lib/workshop-generator";
import { ArrowLeft, Check, X, Plus, Trash2 } from "lucide-react";

interface Props {
  initialGoals: LearningGoalPlan[];
  onBack: () => void;
  onContinue: (goals: LearningGoalPlan[]) => void;
  isLoading?: boolean;
}

export default function WorkshopGoalRefine({ initialGoals, onBack, onContinue, isLoading }: Props) {
  const [goals, setGoals] = useState<LearningGoalPlan[]>(initialGoals);

  const updateGoal = (i: number, patch: Partial<LearningGoalPlan>) =>
    setGoals((g) => g.map((x, idx) => idx === i ? { ...x, ...patch } : x));

  const removeGoal = (i: number) => setGoals((g) => g.filter((_, idx) => idx !== i));

  const addGoal = () =>
    setGoals((g) => [...g, {
      id: `g${Date.now()}`, goal: "", prerequisites: [],
      achieveActivities: [], assessActivities: [], priority: 0,
    }]);

  const acceptChange = (i: number) => {
    updateGoal(i, { originalGoal: undefined }); // Clears originalGoal, marking it as accepted
  };

  const rejectChange = (i: number) => {
    const original = goals[i].originalGoal;
    if (original) {
      updateGoal(i, { goal: original, originalGoal: undefined });
    }
  };

  const cleaned = () => goals.filter((g) => g.goal.trim().length > 0);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <Button variant="ghost" onClick={onBack} className="gap-2 font-body" disabled={isLoading}>
          <ArrowLeft className="h-4 w-4" /> Back
        </Button>
        <Button
          onClick={() => onContinue(cleaned())}
          disabled={isLoading || cleaned().length === 0}
          className="gap-2"
        >
          Continue to Ordering
        </Button>
      </div>

      <Card className="border-primary/20 bg-primary/5 shadow-lg">
        <CardHeader className="py-4">
          <CardTitle className="font-display text-lg">Refine your learning goals</CardTitle>
          <CardDescription className="font-body text-sm">
            Review the generated learning goals. Accept or reject changes from your input, edit text, or add new ones.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {goals.map((g, i) => {
            const hasChange = g.originalGoal && g.originalGoal !== g.goal;
            return (
              <div key={g.id} className="p-4 border border-border/60 rounded-xl bg-card shadow-sm space-y-3 relative group">
                {hasChange && (
                  <div className="bg-amber-500/10 border border-amber-500/20 text-amber-600 text-xs px-3 py-2 rounded-md mb-2">
                    <p className="font-semibold mb-1">Generated goal differs from input:</p>
                    <p className="italic mb-2 opacity-80">"{g.originalGoal}"</p>
                    <div className="flex gap-2">
                      <Button size="sm" variant="outline" className="h-7 text-xs border-amber-500/30 text-amber-600 hover:bg-amber-500/20" onClick={() => acceptChange(i)}>
                        <Check className="h-3 w-3 mr-1" /> Accept Changes
                      </Button>
                      <Button size="sm" variant="outline" className="h-7 text-xs hover:bg-muted" onClick={() => rejectChange(i)}>
                        <X className="h-3 w-3 mr-1" /> Revert
                      </Button>
                    </div>
                  </div>
                )}

                <div className="flex gap-2 items-start">
                  <Input
                    value={g.goal}
                    onChange={(e) => updateGoal(i, { goal: e.target.value })}
                    className={`font-body font-medium ${hasChange ? 'border-amber-500/50 focus-visible:ring-amber-500' : ''}`}
                    disabled={isLoading}
                  />
                  <Button variant="ghost" size="icon" onClick={() => removeGoal(i)} className="text-destructive shrink-0" disabled={isLoading}>
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            );
          })}

          <Button variant="outline" onClick={addGoal} className="gap-1 w-full border-dashed" disabled={isLoading}>
            <Plus className="h-4 w-4" /> Add another learning goal
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
