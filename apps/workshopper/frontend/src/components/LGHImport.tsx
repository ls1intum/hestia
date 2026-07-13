import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Loader2, Plus, Server, Check } from "lucide-react";
import { fetchCourses, fetchGoalsBySession, Course, SessionGroup } from "@/lib/learning-goal-hub";
import { toast } from "@/hooks/use-toast";

interface Props {
  onAddGoals: (goals: string[]) => void;
  disabled?: boolean;
}

export function LGHImport({ onAddGoals, disabled }: Props) {
  const [courses, setCourses] = useState<Course[]>([]);
  const [loadingCourses, setLoadingCourses] = useState(false);
  const [selectedCourseId, setSelectedCourseId] = useState<number | "">("");
  
  const [sessions, setSessions] = useState<SessionGroup[]>([]);
  const [loadingSessions, setLoadingSessions] = useState(false);
  
  const [selectedGoalIds, setSelectedGoalIds] = useState<Set<number>>(new Set());

  useEffect(() => {
    async function load() {
      setLoadingCourses(true);
      try {
        const data = await fetchCourses();
        setCourses(data);
      } catch (e) {
        console.error(e);
        toast({ title: "Error", description: "Failed to fetch courses from LearningGoalHub", variant: "destructive" });
      } finally {
        setLoadingCourses(false);
      }
    }
    load();
  }, []);

  useEffect(() => {
    if (!selectedCourseId) {
      setSessions([]);
      setSelectedGoalIds(new Set());
      return;
    }
    async function loadSessions() {
      setLoadingSessions(true);
      try {
        const data = await fetchGoalsBySession(selectedCourseId as number);
        setSessions(data);
        setSelectedGoalIds(new Set());
      } catch (e) {
        console.error(e);
        toast({ title: "Error", description: "Failed to fetch learning goals", variant: "destructive" });
      } finally {
        setLoadingSessions(false);
      }
    }
    loadSessions();
  }, [selectedCourseId]);

  const toggleGoal = (id: number) => {
    setSelectedGoalIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleAdd = () => {
    const goalsToAdd: string[] = [];
    sessions.forEach((s) => {
      s.goals.forEach((g) => {
        if (selectedGoalIds.has(g.id)) {
          goalsToAdd.push(g.text);
        }
      });
    });
    if (goalsToAdd.length > 0) {
      onAddGoals(goalsToAdd);
      setSelectedGoalIds(new Set());
      toast({ title: "Goals Added", description: `Added ${goalsToAdd.length} goals.` });
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3 rounded-lg border border-border/70 bg-muted/10 px-4 py-3">
        <Server className="h-4 w-4 text-muted-foreground shrink-0" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-body text-foreground font-medium">Import from LearningGoalHub</p>
          <p className="text-xs text-muted-foreground">Select a course to view approved learning goals</p>
        </div>
      </div>
      
      <div className="px-1 space-y-4">
        {loadingCourses ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> Loading courses...
          </div>
        ) : (
          <select 
            className="flex h-9 w-full items-center justify-between rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-sm ring-offset-background placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
            value={selectedCourseId}
            onChange={(e) => setSelectedCourseId(e.target.value ? Number(e.target.value) : "")}
            disabled={disabled || loadingCourses}
          >
            <option value="">-- Select Course --</option>
            {courses.map(c => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        )}

        {loadingSessions && (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> Loading sessions...
          </div>
        )}

        {!loadingSessions && sessions.length > 0 && (
          <div className="space-y-4 mt-4 border border-border rounded-md p-3 max-h-[300px] overflow-y-auto">
            {sessions.map((group, i) => (
              <div key={group.nodeId ?? `group-${i}`} className="space-y-2">
                <h4 className="text-sm font-semibold text-foreground sticky top-0 bg-background py-1">
                  {group.label || "Course Goals"}
                </h4>
                <div className="space-y-1 pl-2">
                  {group.goals.map(goal => (
                    <label key={goal.id} className="flex items-start gap-2 cursor-pointer group">
                      <div className={`mt-0.5 w-4 h-4 rounded-sm border flex items-center justify-center shrink-0 ${selectedGoalIds.has(goal.id) ? 'bg-primary border-primary text-primary-foreground' : 'border-input'}`}>
                        {selectedGoalIds.has(goal.id) && <Check className="h-3 w-3" />}
                      </div>
                      <span className="text-sm text-muted-foreground group-hover:text-foreground">
                        {goal.text}
                      </span>
                      <input 
                        type="checkbox" 
                        className="hidden" 
                        checked={selectedGoalIds.has(goal.id)}
                        onChange={() => toggleGoal(goal.id)}
                      />
                    </label>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}

        {sessions.length > 0 && !loadingSessions && (
          <Button 
            type="button" 
            variant="secondary" 
            size="sm" 
            className="w-full gap-2"
            disabled={selectedGoalIds.size === 0 || disabled}
            onClick={handleAdd}
          >
            <Plus className="h-4 w-4" /> Add {selectedGoalIds.size} Selected Goals
          </Button>
        )}
      </div>
    </div>
  );
}
