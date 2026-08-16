import React, { useState } from "react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { WorkshopInput } from "@/lib/workshop-generator";
import { Loader2, CheckSquare, ArrowLeft, ArrowRight } from "lucide-react";

interface Props {
  initialInput: Partial<WorkshopInput>;
  onGenerate: (input: WorkshopInput) => void;
  isLoading?: boolean;
  onBack?: () => void;
  /** N-1: called on every selection change so parent can persist state across re-mounts */
  onSelectionsChange?: (activities: string[], materials: string[]) => void;
}

const MATERIALS = [
  { emoji: "🖍️", name: "Whiteboard / Blackboard" },
  { emoji: "📽️", name: "Projector / Screen" },
  { emoji: "📝", name: "Sticky Notes & Markers" },
  { emoji: "💻", name: "Laptops / Computers" },
  { emoji: "📄", name: "Handouts / Worksheets" },
  { emoji: "🔘", name: "Clickers / Voting Tools" },
];

export default function WorkshopFormStep2b({ initialInput, onGenerate, isLoading = false, onBack, onSelectionsChange }: Props) {
  const [selectedMaterials, setSelectedMaterials] = useState<string[]>(
    initialInput?.availableMaterials ?? ["Whiteboard / Flipchart", "Projector / Screen"]
  );
  const [customMaterial, setCustomMaterial] = useState("");

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onGenerate({
      ...initialInput,
      availableMaterials: selectedMaterials,
    } as WorkshopInput);
  };

  const toggleMaterial = (mat: string) => {
    setSelectedMaterials(prev => {
      const next = prev.includes(mat) ? prev.filter(m => m !== mat) : [...prev, mat];
      onSelectionsChange?.(initialInput?.selectedActivities ?? [], next);
      return next;
    });
  };

  const addCustomMaterial = () => {
    const v = customMaterial.trim();
    if (v && !selectedMaterials.includes(v)) {
      setSelectedMaterials(prev => {
        const next = [...prev, v];
        onSelectionsChange?.(initialInput?.selectedActivities ?? [], next);
        return next;
      });
      setCustomMaterial("");
    }
  };

  return (
    <div className="space-y-2">
      <Card className="border-border/60 shadow-lg">
        <CardHeader>
          <CardTitle className="font-display text-3xl">Materials</CardTitle>
          <CardDescription>
            Select the materials that will be available during the workshop.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={handleSubmit} className="space-y-6">
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
                    className={`flex items-center gap-2 rounded-md border px-3 py-2 text-left transition-all text-sm ${
                      selectedMaterials.includes(mat.name)
                        ? "border-primary bg-primary/10 text-primary"
                        : "border-border/60 bg-card hover:bg-muted/40"
                    }`}
                  >
                    <CheckSquare className={`h-4 w-4 shrink-0 ${selectedMaterials.includes(mat.name) ? "opacity-100" : "opacity-30"}`} />
                    <span className="truncate">{mat.emoji} {mat.name}</span>
                  </button>
                ))}
              </div>

              {/* Custom material input */}
              <div className="grid grid-cols-2 sm:grid-cols-3 gap-2 pt-1">
                <div className="relative">
                  <Input
                    placeholder="Add custom material…"
                    value={customMaterial}
                    onChange={(e) => setCustomMaterial(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addCustomMaterial(); } }}
                    disabled={isLoading}
                    className="w-full h-9 text-sm"
                  />
                  <Button
                    type="button"
                    variant="outline"
                    onClick={addCustomMaterial}
                    disabled={isLoading || !customMaterial.trim()}
                    className="absolute left-full top-0 ml-1 h-8 w-8 shrink-0 p-0 text-xs font-semibold"
                    title="Add custom material"
                  >
                    +
                  </Button>
                </div>
              </div>
            </div>

            {/* Navigation */}
            <div className="flex justify-between pt-2">
              {onBack ? (
                <Button type="button" variant="outline" onClick={onBack} disabled={isLoading} className="gap-2 font-body">
                  <ArrowLeft className="h-4 w-4" /> Previous step
                </Button>
              ) : <div />}
              <Button
                type="submit"
                size="lg"
                className="px-10 font-body font-semibold text-base gap-2"
                disabled={isLoading}
              >
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
