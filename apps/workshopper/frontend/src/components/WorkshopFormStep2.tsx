import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { extractPdfText } from "@/lib/pdf-parser";
import { WorkshopInput } from "@/lib/workshop-generator";
import { Loader2, Sparkles, CheckSquare, Info, X, ArrowLeft, ArrowRight } from "lucide-react";

interface Props {
  initialInput: Partial<WorkshopInput>;
  onGenerate: (input: WorkshopInput) => void;
  isLoading?: boolean;
  onBack?: () => void;
  /** N-1: called on every selection change so parent can persist state across re-mounts */
  onSelectionsChange?: (activities: string[], materials: string[]) => void;
}

const ACTIVITIES = [
  { emoji: "🗣️", name: "Group Discussion", desc: "An open conversation among participants to explore a topic collaboratively, share perspectives, and build on each other's ideas. Best for surfacing diverse viewpoints and encouraging active listening." },
  { emoji: "🕵️‍♂️", name: "Case Study", desc: "An in-depth analysis of a real or realistic scenario, where participants examine context, decisions, and outcomes to extract practical lessons. Ideal for applying theory to real-world situations." },
  { emoji: "🎭", name: "Role Play", desc: "Participants act out defined roles or scenarios to practice skills, explore perspectives, or simulate real-life interactions in a low-stakes environment. Great for building empathy and interpersonal skills." },
  { emoji: "🛠️", name: "Hands-on Practice", desc: "A guided, practical exercise where participants directly apply a skill or tool themselves rather than just observing. Reinforces learning through doing and immediate feedback." },
  { emoji: "✅", name: "Quiz / Polls", desc: "Short, structured questions used to check understanding, gather opinions, or gauge the room in real time. Quick to run and useful for engagement or knowledge checks." },
  { emoji: "🙋‍♀️", name: "Q&A Session", desc: "A dedicated segment where participants can ask questions and receive direct answers from a facilitator or expert. Clarifies doubts and encourages open dialogue." },
  { emoji: "🤝", name: "Peer Review", desc: "Participants evaluate and give constructive feedback on each other's work. Builds critical thinking and exposes people to different approaches and standards." },
  { emoji: "💡", name: "Brainstorming", desc: "A free-flowing idea-generation session where quantity and creativity are prioritized over immediate judgment. Useful for problem-solving and innovation." },
  { emoji: "🧠", name: "Think-Pair-Share", desc: "A three-step structure: individuals first reflect alone, then discuss with a partner, then share with the wider group. Balances independent thinking with collaborative discussion." },
];

const MATERIALS = [
  { emoji: "🖍️", name: "Whiteboard / Blackboard" },
  { emoji: "📽️", name: "Projector / Screen" },
  { emoji: "📝", name: "Sticky Notes & Markers" },
  { emoji: "💻", name: "Laptops / Computers" },
  { emoji: "📄", name: "Handouts / Worksheets" },
  { emoji: "🔘", name: "Clickers / Voting Tools" },
];

export default function WorkshopFormStep2({ initialInput, onGenerate, isLoading = false, onBack, onSelectionsChange }: Props) {
  const [selectedActivities, setSelectedActivities] = useState<string[]>(initialInput?.selectedActivities ?? []);
  const [customActivity, setCustomActivity] = useState("");
  const [selectedMaterials, setSelectedMaterials] = useState<string[]>(initialInput?.availableMaterials ?? ["Whiteboard / Flipchart", "Projector / Screen"]);
  const [customMaterial, setCustomMaterial] = useState("");

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onGenerate({
      ...initialInput,
      selectedActivities,
      availableMaterials: selectedMaterials,
    } as WorkshopInput);
  };

  const toggleActivity = (act: string) => {
    setSelectedActivities(prev => {
      const next = prev.includes(act) ? prev.filter(a => a !== act) : [...prev, act];
      // N-1: report updated selections to parent immediately
      onSelectionsChange?.(next, selectedMaterials);
      return next;
    });
  };

  const addCustomActivity = () => {
    const v = customActivity.trim();
    if (v && !selectedActivities.includes(v)) {
      setSelectedActivities(prev => {
        const next = [...prev, v];
        onSelectionsChange?.(next, selectedMaterials);
        return next;
      });
      setCustomActivity("");
    }
  };

  const toggleMaterial = (mat: string) => {
    setSelectedMaterials(prev => {
      const next = prev.includes(mat) ? prev.filter(m => m !== mat) : [...prev, mat];
      // N-1: report updated selections to parent immediately
      onSelectionsChange?.(selectedActivities, next);
      return next;
    });
  };

  const addCustomMaterial = () => {
    const v = customMaterial.trim();
    if (v && !selectedMaterials.includes(v)) {
      setSelectedMaterials(prev => {
        const next = [...prev, v];
        onSelectionsChange?.(selectedActivities, next);
        return next;
      });
      setCustomMaterial("");
    }
  };



  return (
    <div className="space-y-2">
      <Card className="border-border/60 shadow-lg">
        <CardHeader>
          <CardTitle className="font-display text-3xl">Activities &amp; Materials</CardTitle>
          <CardDescription>
            Pick the activities you'd like incorporated and the materials that are available.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-6">
            {/* Activities Checklist */}
            <div className="space-y-2">
              <Label className="font-body font-medium">
                Preferred Activities{" "}
                <span className="text-muted-foreground font-normal">(select all that apply)</span>
              </Label>
              <TooltipProvider delayDuration={250}>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  {ACTIVITIES.map((act) => (
                    <div key={act.name} className="flex items-center gap-1">
                      <button
                        type="button"
                        disabled={isLoading}
                        onClick={() => toggleActivity(act.name)}
                        className={`flex-1 flex items-center gap-2 rounded-md border px-3 py-2 text-left transition-all text-sm ${selectedActivities.includes(act.name)
                          ? "border-primary bg-primary/10 text-primary"
                          : "border-border/60 bg-card hover:bg-muted/40"
                          }`}
                      >
                        <CheckSquare
                          className={`h-4 w-4 shrink-0 ${selectedActivities.includes(act.name) ? "opacity-100" : "opacity-30"}`}
                        />
                        <span className="truncate">{act.emoji} {act.name}</span>
                      </button>
                      <Tooltip>
                        <TooltipTrigger asChild>
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon"
                            className="h-8 w-8 shrink-0 text-muted-foreground hover:text-foreground"
                          >
                            <Info className="h-4 w-4" />
                          </Button>
                        </TooltipTrigger>
                        <TooltipContent side="right" className="max-w-xs text-sm">
                          <p>{act.desc}</p>
                        </TooltipContent>
                      </Tooltip>
                    </div>
                  ))}
                  {selectedActivities.filter(a => !ACTIVITIES.some(act => act.name === a)).map((act) => (
                    <div key={act} className="flex items-center gap-1">
                      <button
                        type="button"
                        disabled={isLoading}
                        onClick={() => toggleActivity(act)}
                        className={`flex-1 flex items-center gap-2 rounded-md border px-3 py-2 text-left transition-all text-sm ${selectedActivities.includes(act)
                          ? "border-primary bg-primary/10 text-primary"
                          : "border-border/60 bg-card hover:bg-muted/40"
                          }`}
                      >
                        <CheckSquare className={`h-4 w-4 shrink-0 ${selectedActivities.includes(act) ? "opacity-100" : "opacity-30"}`} />
                        <span className="truncate">{act}</span>
                      </button>
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        className="h-8 w-8 shrink-0 text-muted-foreground hover:text-destructive"
                        onClick={() => toggleActivity(act)}
                      >
                        <X className="h-4 w-4" />
                      </Button>
                    </div>
                  ))}
                  <div className="flex gap-1 items-center">
                    <Input
                      placeholder="Add custom activity…"
                      value={customActivity}
                      onChange={(e) => setCustomActivity(e.target.value)}
                      onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addCustomActivity(); } }}
                      disabled={isLoading}
                    />
                    <Button type="button" variant="outline" onClick={addCustomActivity} disabled={isLoading || !customActivity.trim()} className="shrink-0">
                      Add
                    </Button>
                  </div>
                </div>
              </TooltipProvider>
            </div>

            {/* Materials Checklist */}
            <div className="space-y-2">
              <Label className="font-body font-medium">
                Available Materials{" "}
                <span className="text-muted-foreground font-normal">(select what's available)</span>
              </Label>
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                {MATERIALS.map((mat) => (
                  <button
                    key={mat.name}
                    type="button"
                    disabled={isLoading}
                    onClick={() => toggleMaterial(mat.name)}
                    className={`flex items-center gap-2 rounded-md border px-3 py-2 text-left transition-all text-sm ${selectedMaterials.includes(mat.name)
                      ? "border-primary bg-primary/10 text-primary"
                      : "border-border/60 bg-card hover:bg-muted/40"
                      }`}
                  >
                    <CheckSquare className={`h-4 w-4 shrink-0 ${selectedMaterials.includes(mat.name) ? "opacity-100" : "opacity-30"}`} />
                    <span className="truncate">{mat.emoji} {mat.name}</span>
                  </button>
                ))}

                <div className="flex gap-1 items-center">
                  <Input
                    placeholder="Add custom material…"
                    value={customMaterial}
                    onChange={(e) => setCustomMaterial(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addCustomMaterial(); } }}
                    disabled={isLoading}
                  />
                  <Button type="button" variant="outline" onClick={addCustomMaterial} disabled={isLoading || !customMaterial.trim()} className="shrink-0">
                    Add
                  </Button>
                </div>
              </div>
            </div>

            {/* Navigation — bottom */}
            <div className="flex justify-between pt-2">
              {onBack ? (
                <Button type="button" variant="outline" onClick={onBack} disabled={isLoading} className="gap-2 font-body">
                  <ArrowLeft className="h-4 w-4" /> Previous step
                </Button>
              ) : <div />}
              <Button type="submit" size="lg" className="px-10 font-body font-semibold text-base gap-2" disabled={isLoading}>
                {isLoading ? (
                  <><Loader2 className="h-5 w-5 animate-spin" /> Generating…</>
                ) : (
                  <>Next step <ArrowRight className="h-4 w-4" /></>
                )}
              </Button>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
