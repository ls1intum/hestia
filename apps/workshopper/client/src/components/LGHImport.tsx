import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Loader2, Plus, Server } from "lucide-react";
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
  
  const [selectedGoalId, setSelectedGoalId] = useState<number | "">("");

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
      setSelectedGoalId("");
      return;
    }
    async function loadSessions() {
      setLoadingSessions(true);
      try {
        const data = await fetchGoalsBySession(selectedCourseId as number);
        setSessions(data);
        setSelectedGoalId("");
      } catch (e) {
        console.error(e);
        toast({ title: "Error", description: "Failed to fetch learning goals", variant: "destructive" });
      } finally {
        setLoadingSessions(false);
      }
    }
    loadSessions();
  }, [selectedCourseId]);

  const handleAdd = () => {
    if (selectedGoalId === "") return;
    
    // Find the text of the selected goal
    let goalText = "";
    for (const s of sessions) {
      const g = s.goals.find(g => g.id === selectedGoalId);
      if (g) {
        goalText = g.text;
        break;
      }
    }
    
    if (goalText) {
      onAddGoals([goalText]);
      setSelectedGoalId("");
      toast({ title: "Goal Added", description: "Successfully imported learning goal." });
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3 rounded-lg border border-border/70 bg-muted/10 px-4 py-3">
        <Server className="h-4 w-4 text-muted-foreground shrink-0" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-body text-foreground font-medium">Import from LearningGoalHub</p>
          <p className="text-xs text-muted-foreground">Select a course and a learning goal</p>
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
            <Loader2 className="h-4 w-4 animate-spin" /> Loading learning goals...
          </div>
        )}

        {!loadingSessions && sessions.length > 0 && (
          <select
            className="flex h-9 w-full items-center justify-between rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-sm ring-offset-background placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
            value={selectedGoalId}
            onChange={(e) => setSelectedGoalId(e.target.value ? Number(e.target.value) : "")}
            disabled={disabled}
          >
            <option value="">-- Select Learning Goal --</option>
            {sessions.map((group, i) => (
              <optgroup key={group.nodeId ?? `group-${i}`} label={group.label || "Course Goals"}>
                {group.goals.map(goal => (
                  <option key={goal.id} value={goal.id}>
                    {goal.text.length > 80 ? goal.text.substring(0, 80) + "..." : goal.text}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        )}

        {sessions.length > 0 && !loadingSessions && (
          <Button 
            type="button" 
            variant="secondary" 
            size="sm" 
            className="w-full gap-2"
            disabled={selectedGoalId === "" || disabled}
            onClick={handleAdd}
          >
            <Plus className="h-4 w-4" /> Add Goal
          </Button>
        )}
      </div>
    </div>
  );
}
