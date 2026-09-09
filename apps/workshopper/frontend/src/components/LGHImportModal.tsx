import { useState, useEffect, useMemo } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Loader2, Server, Search, ChevronRight, ChevronDown } from "lucide-react";
import { fetchCourses, fetchGoalsBySession, Course, LearningGoal } from "@/lib/learning-goal-hub";
import { toast } from "@/hooks/use-toast";
import { SubSkill } from "@/lib/workshop-generator";

export interface SelectedSkillData {
  id: string;
  goal: string;
  bloomLevel?: string;
  soloLevel?: string;
  session?: string;
  subSkills: SubSkill[];
}

interface Props {
  onAddSkills: (skills: SelectedSkillData[]) => void;
  disabled?: boolean;
}

interface SkillNode {
  skill: LearningGoal;
  session?: string;
  subSkills: LearningGoal[];
}

export function LGHImportModal({ onAddSkills, disabled }: Props) {
  const [open, setOpen] = useState(false);
  const [courses, setCourses] = useState<Course[]>([]);
  const [loadingCourses, setLoadingCourses] = useState(false);
  const [selectedCourseId, setSelectedCourseId] = useState<number | "">("");

  const [loadingSessions, setLoadingSessions] = useState(false);
  const [skillNodes, setSkillNodes] = useState<SkillNode[]>([]);

  const [searchQuery, setSearchQuery] = useState("");
  const [expandedNodes, setExpandedNodes] = useState<Set<number>>(new Set());

  // Selection state keeps track of selected skill IDs.
  const [selectedSkillIds, setSelectedSkillIds] = useState<Set<number>>(new Set());

  useEffect(() => {
    if (open && courses.length === 0) {
      loadCourses();
    }
  }, [open]);

  async function loadCourses() {
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

  useEffect(() => {
    if (!selectedCourseId) {
      setSkillNodes([]);
      setSelectedSkillIds(new Set());
      return;
    }

    async function loadSessions() {
      setLoadingSessions(true);
      try {
        const data = await fetchGoalsBySession(selectedCourseId as number);

        // Build the skill tree from flat list + relationships.
        const allGoals = new Map<number, LearningGoal>();
        const sessionMap = new Map<number, string>();

        data.forEach(group => {
          group.goals.forEach(g => {
            allGoals.set(g.id, g);
            if (group.label) {
              sessionMap.set(g.id, group.label);
            }
          });
        });

        const nodes: SkillNode[] = [];

        allGoals.forEach(goal => {
          // LGH UI strictly uses TERMINAL goals as the roots of the Competency Tree
          if (goal.origin === "TERMINAL") {
            // Find its immediate subskills (goals that contribute to it)
            const subSkills: LearningGoal[] = [];
            allGoals.forEach(potentialSub => {
              const contributesToThis = potentialSub.relationships?.some(r => r.type === "CONTRIBUTES_TO" && r.targetGoalId === goal.id);
              if (contributesToThis) {
                subSkills.push(potentialSub);
              }
            });

            // Sort subskills by lecture order to match LGH
            subSkills.sort((a, b) => (a.lectureOrder ?? Number.MAX_SAFE_INTEGER) - (b.lectureOrder ?? Number.MAX_SAFE_INTEGER));

            nodes.push({
              skill: goal,
              session: sessionMap.get(goal.id) || goal.hierarchy?.session || "Module level",
              subSkills
            });
          }
        });

        // Sort roots by lecture order to match LGH
        nodes.sort((a, b) => (a.skill.lectureOrder ?? Number.MAX_SAFE_INTEGER) - (b.skill.lectureOrder ?? Number.MAX_SAFE_INTEGER));

        setSkillNodes(nodes);
        setExpandedNodes(new Set());
        setSelectedSkillIds(new Set());
      } catch (e) {
        console.error(e);
        toast({ title: "Error", description: "Failed to fetch learning goals", variant: "destructive" });
      } finally {
        setLoadingSessions(false);
      }
    }

    loadSessions();
  }, [selectedCourseId]);

  const toggleExpand = (id: number) => {
    setExpandedNodes(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleSelectSkill = (id: number) => {
    setSelectedSkillIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const filteredNodes = useMemo(() => {
    if (!searchQuery) return skillNodes;
    const lowerQ = searchQuery.toLowerCase();
    return skillNodes.filter(node =>
      node.skill.text.toLowerCase().includes(lowerQ) ||
      node.subSkills.some(sub => sub.text.toLowerCase().includes(lowerQ)) ||
      (node.session && node.session.toLowerCase().includes(lowerQ))
    );
  }, [skillNodes, searchQuery]);

  const handleAdd = () => {
    const selectedData: SelectedSkillData[] = [];

    skillNodes.forEach(node => {
      if (selectedSkillIds.has(node.skill.id)) {
        selectedData.push({
          id: `lgh-${node.skill.id}`,
          goal: node.skill.text,
          bloomLevel: node.skill.bloomLevel,
          soloLevel: node.skill.soloLevel,
          session: node.session,
          subSkills: node.subSkills.map(sub => ({
            id: `lgh-${sub.id}`,
            text: sub.text,
            bloomLevel: sub.bloomLevel,
            soloLevel: sub.soloLevel
          }))
        });
      }
    });

    if (selectedData.length > 0) {
      onAddSkills(selectedData);
      setOpen(false);
      toast({ title: "Skills Added", description: `Successfully imported ${selectedData.length} skill(s).` });
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <button
          className="flex w-full items-center gap-3 rounded-lg border border-border/70 bg-muted/10 px-4 py-3 text-left transition-colors hover:bg-muted/30 focus:outline-none focus:ring-2 focus:ring-primary disabled:opacity-50 disabled:cursor-not-allowed"
          disabled={disabled}
        >
          <Server className="h-5 w-5 text-muted-foreground shrink-0" />
          <div className="flex-1 min-w-0">
            <p className="text-sm font-body text-foreground font-medium">Import from LearningGoalHub</p>
            <p className="text-xs text-muted-foreground">Select a course and a learning goal</p>
          </div>
        </button>
      </DialogTrigger>

      <DialogContent className="!max-w-[95vw] sm:!max-w-[1000px] !w-[95vw] h-[90vh] max-h-[95vh] flex flex-col p-0 overflow-hidden">
        <DialogHeader className="p-6 pb-2 border-b">
          <DialogTitle className="font-display text-2xl">Browse Skill Set</DialogTitle>
          <p className="text-sm text-muted-foreground">Search and select skills or sub-skills</p>
        </DialogHeader>

        <div className="p-6 pt-4 space-y-4 flex-1 flex flex-col overflow-hidden">
          <div className="flex gap-4">
            <div className="w-1/3">
              {loadingCourses ? (
                <div className="flex items-center gap-2 text-sm text-muted-foreground h-10">
                  <Loader2 className="h-4 w-4 animate-spin" /> Loading courses...
                </div>
              ) : (
                <select
                  className="flex h-10 w-full items-center justify-between rounded-md border border-input bg-transparent px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
                  value={selectedCourseId}
                  onChange={(e) => setSelectedCourseId(e.target.value ? Number(e.target.value) : "")}
                >
                  <option value="">-- Select Course --</option>
                  {courses.map(c => (
                    <option key={c.id} value={c.id}>{c.name}</option>
                  ))}
                </select>
              )}
            </div>

            <div className="relative flex-1">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <input
                type="text"
                placeholder="Search skills..."
                className="flex h-10 w-full rounded-md border border-input bg-transparent px-3 py-2 pl-9 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-ring"
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                disabled={!selectedCourseId || loadingSessions}
              />
            </div>
          </div>

          <div className="flex-1 border rounded-md overflow-hidden flex flex-col bg-muted/10">
            <div className="grid grid-cols-[auto_1fr_80px_150px] items-center gap-4 px-4 py-3 bg-muted/30 border-b text-xs font-semibold text-muted-foreground uppercase tracking-wider">
              <div className="w-6"></div>
              <div>Learning Goal</div>
              <div className="text-center">Level</div>
              <div>Session</div>
            </div>

            <div className="flex-1 overflow-y-auto">
              {loadingSessions ? (
                <div className="flex items-center justify-center p-8 text-muted-foreground">
                  <Loader2 className="h-6 w-6 animate-spin mr-2" /> Loading skills...
                </div>
              ) : !selectedCourseId ? (
                <div className="flex items-center justify-center p-8 text-muted-foreground text-sm">
                  Select a course to view skills
                </div>
              ) : filteredNodes.length === 0 ? (
                <div className="flex items-center justify-center p-8 text-muted-foreground text-sm">
                  No skills found
                </div>
              ) : (
                <div className="divide-y divide-border/50">
                  {filteredNodes.map((node, index) => (
                    <div key={node.skill.id} className="group">
                      <div className="grid grid-cols-[auto_1fr_80px_150px] items-center gap-4 px-4 py-3 hover:bg-muted/20 transition-colors">
                        <div className="flex items-center gap-2">
                          <input
                            type="checkbox"
                            className="h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary"
                            checked={selectedSkillIds.has(node.skill.id)}
                            onChange={() => toggleSelectSkill(node.skill.id)}
                          />
                          <button
                            className="p-0.5 rounded-sm hover:bg-muted/50 text-muted-foreground"
                            onClick={() => toggleExpand(node.skill.id)}
                          >
                            {expandedNodes.has(node.skill.id) ? (
                              <ChevronDown className="h-4 w-4" />
                            ) : (
                              <ChevronRight className="h-4 w-4" />
                            )}
                          </button>
                        </div>

                        <div className="text-sm font-medium pr-4">
                          {index + 1}. {node.skill.text}
                        </div>

                        <div className="text-center">
                          <span className="inline-flex items-center px-2 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wider bg-[#d9cbb8]/30 text-[#8b7556]">
                            SKILL
                          </span>
                        </div>

                        <div className="text-xs text-muted-foreground truncate" title={node.session}>
                          {node.session || "-"}
                        </div>
                      </div>

                      {expandedNodes.has(node.skill.id) && node.subSkills.length > 0 && (
                        <div className="bg-muted/5">
                          {node.subSkills.map((sub, subIdx) => (
                            <div key={sub.id} className="grid grid-cols-[auto_1fr_80px_150px] items-center gap-4 px-4 py-2 border-t border-border/30 hover:bg-muted/20 transition-colors">
                              <div className="flex items-center gap-2">
                                {/* Invisible placeholder for checkbox alignment */}
                                <div className="w-[18px]"></div>
                                {/* Visual tree line indicator */}
                                <div className="h-4 w-px bg-border ml-2"></div>
                              </div>

                              <div className="text-sm text-muted-foreground pl-2 pr-4">
                                {index + 1}.{subIdx + 1}. {sub.text}
                              </div>

                              <div className="text-center">
                                <span className="inline-flex items-center px-2 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wider bg-[#b8cad9]/30 text-[#4c6780]">
                                  SUB-SKILL
                                </span>
                              </div>

                              <div className="text-xs text-muted-foreground truncate" title={sub.hierarchy?.session || "Extracted goal"}>
                                {sub.hierarchy?.session || "-"}
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>

        <div className="p-4 border-t flex justify-between items-center bg-muted/10">
          <div className="text-sm text-muted-foreground">
            {selectedSkillIds.size === 0
              ? "Nothing selected yet"
              : `${selectedSkillIds.size} skill(s) selected`}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => setOpen(false)}>Cancel</Button>
            <Button onClick={handleAdd} disabled={selectedSkillIds.size === 0}>Add Skills</Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
