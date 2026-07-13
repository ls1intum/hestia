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
}

const ACTIVITIES = [
  { emoji: "🗣️", name: "Group Discussion", desc: "Participants discuss a topic in small or large groups to share perspectives." },
  { emoji: "🕵️‍♂️", name: "Case Study", desc: "In-depth analysis of a real-world scenario to apply theoretical knowledge." },
  { emoji: "🎭", name: "Role Play", desc: "Participants act out roles in a specific situation to practice skills." },
  { emoji: "🛠️", name: "Hands-on Practice", desc: "Active application of a skill or tool in a controlled environment." },
  { emoji: "✅", name: "Quiz / Polls", desc: "Brief assessments or surveys to check understanding or gather opinions." },
  { emoji: "🙋‍♀️", name: "Q&A Session", desc: "Dedicated time for participants to ask questions and get answers." },
  { emoji: "🤝", name: "Peer Review", desc: "Participants evaluate and provide feedback on each other's work." },
  { emoji: "💡", name: "Brainstorming", desc: "Creative idea generation in a group without immediate judgement." },
  { emoji: "🧠", name: "Think-Pair-Share", desc: "Students think individually, discuss with a partner, then share with the class." },
];

const MATERIALS = [
  { emoji: "🖍️", name: "Whiteboard / Blackboard" },
  { emoji: "📽️", name: "Projector / Screen" },
  { emoji: "📝", name: "Sticky Notes & Markers" },
  { emoji: "💻", name: "Laptops / Computers" },
  { emoji: "📄", name: "Handouts / Worksheets" },
  { emoji: "🔘", name: "Clickers / Voting Tools" },
];

export default function WorkshopFormStep2({ initialInput, onGenerate, isLoading = false, onBack }: Props) {
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

  const toggleActivity = (act: string) =>
    setSelectedActivities(prev =>
      prev.includes(act) ? prev.filter(a => a !== act) : [...prev, act]
    );

  const addCustomActivity = () => {
    const v = customActivity.trim();
    if (v && !selectedActivities.includes(v)) {
      setSelectedActivities(prev => [...prev, v]);
      setCustomActivity("");
    }
  };

  const toggleMaterial = (mat: string) =>
    setSelectedMaterials(prev =>
      prev.includes(mat) ? prev.filter(m => m !== mat) : [...prev, mat]
    );

  const addCustomMaterial = () => {
    const v = customMaterial.trim();
    if (v && !selectedMaterials.includes(v)) {
      setSelectedMaterials(prev => [...prev, v]);
      setCustomMaterial("");
    }
  };



  return (
    <div className="space-y-2">
      <Card className="border-border/60 shadow-lg">
        <CardHeader>
          <CardTitle className="font-display text-3xl">Step 2: Activities &amp; Materials</CardTitle>
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
                </div>
              </TooltipProvider>

              {/* Custom activity */}
              <div className="flex gap-2 mt-2">
                <Input
                  placeholder="Add custom activity…"
                  value={customActivity}
                  onChange={(e) => setCustomActivity(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addCustomActivity(); } }}
                  disabled={isLoading}
                />
                <Button type="button" variant="outline" onClick={addCustomActivity} disabled={isLoading || !customActivity.trim()}>
                  Add
                </Button>
              </div>

              {/* Custom activity chips */}
              {selectedActivities.filter(a => !ACTIVITIES.some(act => act.name === a)).length > 0 && (
                <div className="flex flex-wrap gap-2 mt-1">
                  {selectedActivities.filter(a => !ACTIVITIES.some(act => act.name === a)).map(act => (
                    <span key={act} className="inline-flex items-center gap-1 bg-primary/10 text-primary text-xs px-2 py-1 rounded-full font-body">
                      {act}
                      <button type="button" onClick={() => toggleActivity(act)} className="hover:text-destructive">
                        <X className="h-3 w-3" />
                      </button>
                    </span>
                  ))}
                </div>
              )}
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
              </div>

              {/* Custom material */}
              <div className="flex gap-2 mt-2">
                <Input
                  placeholder="Add custom material…"
                  value={customMaterial}
                  onChange={(e) => setCustomMaterial(e.target.value)}
                  onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addCustomMaterial(); } }}
                  disabled={isLoading}
                />
                <Button type="button" variant="outline" onClick={addCustomMaterial} disabled={isLoading || !customMaterial.trim()}>
                  Add
                </Button>
              </div>

              {/* Custom material chips */}
              {selectedMaterials.filter(m => !MATERIALS.some(mat => mat.name === m)).length > 0 && (
                <div className="flex flex-wrap gap-2 mt-1">
                  {selectedMaterials.filter(m => !MATERIALS.some(mat => mat.name === m)).map(mat => (
                    <span key={mat} className="inline-flex items-center gap-1 bg-primary/10 text-primary text-xs px-2 py-1 rounded-full font-body">
                      {mat}
                      <button type="button" onClick={() => toggleMaterial(mat)} className="hover:text-destructive">
                        <X className="h-3 w-3" />
                      </button>
                    </span>
                  ))}
                </div>
              )}
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
