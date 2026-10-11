import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { LearningGoalPlan } from "@/lib/workshop-generator";
import { ArrowLeft, ArrowUp, ArrowDown, Plus, Trash2, X, Sparkles, Loader2, Star, ChevronDown, Package } from "lucide-react";

interface Props {
  initialGoals: LearningGoalPlan[];
  onBack: () => void;
  onContinue: (goals: LearningGoalPlan[], availableMaterials: string) => void;
  isLoading?: boolean;
}

// ── 5-star priority rating widget ────────────────────────────────
const StarRating = ({
  value,
  onChange,
}: {
  value: number;
  onChange: (v: number) => void;
}) => {
  const [hovered, setHovered] = useState(0);

  const labels: Record<number, string> = {
    1: "Low priority",
    2: "Below average",
    3: "Average priority",
    4: "High priority",
    5: "Highest priority",
  };

  return (
    <div className="flex items-center gap-0.5" title={value > 0 ? labels[value] : "Set priority"}>
      {[1, 2, 3, 4, 5].map((star) => {
        const active = star <= (hovered || value);
        return (
          <button
            key={star}
            type="button"
            onMouseEnter={() => setHovered(star)}
            onMouseLeave={() => setHovered(0)}
            onClick={() => onChange(star === value ? 0 : star)}
            className={`h-7 w-7 flex items-center justify-center rounded transition-colors ${
              active
                ? "text-amber-400"
                : "text-muted-foreground/30 hover:text-amber-300"
            }`}
            title={star === value ? "Click to clear" : labels[star]}
            aria-label={`${star} star${star !== 1 ? "s" : ""}`}
          >
            <Star
              className="h-4 w-4"
              fill={active ? "currentColor" : "none"}
              strokeWidth={1.5}
            />
          </button>
        );
      })}
      {value > 0 && (
        <span className="ml-1 text-xs text-amber-500 font-body font-medium">
          {labels[value]}
        </span>
      )}
    </div>
  );
};

// ── Compact editable list ─────────────────────────────────────────
const ItemList = ({
  items, onAdd, onUpdate, onRemove, placeholder,
}: {
  items: string[];
  onAdd: () => void;
  onUpdate: (j: number, v: string) => void;
  onRemove: (j: number) => void;
  placeholder: string;
}) => (
  <div className="space-y-1">
    {items.length === 0 && (
      <p className="text-xs font-body italic text-muted-foreground">None yet.</p>
    )}
    {items.map((m, j) => (
      <div key={j} className="flex gap-1">
        <Input
          value={m}
          placeholder={placeholder}
          onChange={(e) => onUpdate(j, e.target.value)}
          className="h-7 text-xs"
        />
        <Button type="button" variant="ghost" size="icon" className="h-7 w-7" onClick={() => onRemove(j)}>
          <X className="h-3 w-3" />
        </Button>
      </div>
    ))}
    <Button type="button" variant="outline" size="sm" onClick={onAdd} className="gap-1 h-6 text-xs px-2">
      <Plus className="h-3 w-3" /> Add
    </Button>
  </div>
);

// ── Tag input for available materials ────────────────────────────
const TagInput = ({
  tags, onChange, placeholder,
}: {
  tags: string[];
  onChange: (tags: string[]) => void;
  placeholder: string;
}) => {
  const [input, setInput] = useState("");
  const commit = () => {
    const val = input.trim();
    if (val) onChange([...tags, val]);
    setInput("");
  };
  return (
    <div
      className="flex flex-wrap gap-1.5 p-2 border border-border/60 rounded-md bg-muted/20 min-h-[40px] cursor-text"
      onClick={() => document.getElementById("materials-tag-input")?.focus()}
    >
      {tags.map((t, i) => (
        <span key={i} className="flex items-center gap-1 bg-primary/10 text-primary text-xs px-2 py-0.5 rounded-full font-body">
          {t}
          <button
            type="button"
            onClick={(e) => { e.stopPropagation(); onChange(tags.filter((_, j) => j !== i)); }}
            className="hover:text-destructive"
          >
            <X className="h-3 w-3" />
          </button>
        </span>
      ))}
      <input
        id="materials-tag-input"
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === ",") { e.preventDefault(); commit(); }
          else if (e.key === "Backspace" && !input && tags.length > 0) onChange(tags.slice(0, -1));
        }}
        onBlur={commit}
        placeholder={tags.length === 0 ? placeholder : ""}
        className="flex-1 min-w-[160px] bg-transparent text-sm outline-none placeholder:text-muted-foreground/60 font-body"
      />
    </div>
  );
};

// ── Priority border colour helper ─────────────────────────────────
function priorityBorderClass(p: number | undefined): string {
  switch (p) {
    case 5: return "border-amber-400/80 ring-1 ring-amber-400/50";
    case 4: return "border-amber-400/50 ring-1 ring-amber-300/30";
    case 3: return "border-amber-300/40";
    case 2: return "border-amber-200/30";
    case 1: return "border-border/60";
    default: return "border-border/60";
  }
}

// ── Priority label shown next to goal title ───────────────────────
const PRIORITY_LABELS: Record<number, string> = {
  5: "★★★★★ Highest",
  4: "★★★★ High",
  3: "★★★ Average",
  2: "★★ Low",
  1: "★ Minimal",
};

// ── Main component ────────────────────────────────────────────────
export default function WorkshopPlanReview({ initialGoals, onBack, onContinue, isLoading }: Props) {
  const [goals, setGoals] = useState<LearningGoalPlan[]>(initialGoals);
  const [materialTags, setMaterialTags] = useState<string[]>([]);
  const [detailsOpen, setDetailsOpen] = useState<Set<number>>(new Set([0]));

  const toggleDetails = (i: number) =>
    setDetailsOpen((prev) => {
      const next = new Set(prev);
      if (next.has(i)) next.delete(i); else next.add(i);
      return next;
    });

  const setPriority = (i: number, p: number) =>
    setGoals((g) => g.map((x, idx) => idx === i ? { ...x, priority: p } : x));

  const updateGoal = (i: number, patch: Partial<LearningGoalPlan>) =>
    setGoals((g) => g.map((x, idx) => (idx === i ? { ...x, ...patch } : x)));

  const move = (i: number, dir: -1 | 1) =>
    setGoals((g) => {
      const j = i + dir;
      if (j < 0 || j >= g.length) return g;
      const copy = [...g];
      [copy[i], copy[j]] = [copy[j], copy[i]];
      return copy;
    });

  const removeGoal = (i: number) => setGoals((g) => g.filter((_, idx) => idx !== i));

  const addGoal = () =>
    setGoals((g) => [
      ...g,
      { id: `g${Date.now()}`, goal: "", prerequisites: [], achieveActivities: [], assessActivities: [], priority: 0 },
    ]);

  const addItem = (i: number, key: "prerequisites" | "achieveActivities" | "assessActivities") =>
    updateGoal(i, { [key]: [...goals[i][key], ""] } as Partial<LearningGoalPlan>);

  const updateItem = (i: number, key: "prerequisites" | "achieveActivities" | "assessActivities", j: number, val: string) => {
    const next = [...goals[i][key]];
    next[j] = val;
    updateGoal(i, { [key]: next } as Partial<LearningGoalPlan>);
  };

  const removeItem = (i: number, key: "prerequisites" | "achieveActivities" | "assessActivities", j: number) =>
    updateGoal(i, { [key]: goals[i][key].filter((_, idx) => idx !== j) } as Partial<LearningGoalPlan>);

  const cleaned = () =>
    goals
      .filter((g) => g.goal.trim().length > 0)
      .map((g) => ({
        ...g,
        prerequisites: g.prerequisites.map((s) => s.trim()).filter(Boolean),
        achieveActivities: g.achieveActivities.map((s) => s.trim()).filter(Boolean),
        assessActivities: g.assessActivities.map((s) => s.trim()).filter(Boolean),
      }));

  return (
    <div className="space-y-4">
      {/* Nav */}
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <Button variant="ghost" onClick={onBack} className="gap-2 font-body">
          <ArrowLeft className="h-4 w-4" /> Back
        </Button>
        <Button
          onClick={() => onContinue(cleaned(), materialTags.join(", "))}
          disabled={isLoading || cleaned().length === 0}
          className="gap-2"
        >
          {isLoading ? (
            <><Loader2 className="h-4 w-4 animate-spin" /> Generating session…</>
          ) : (
            <><Sparkles className="h-4 w-4" /> Continue & Generate Session</>
          )}
        </Button>
      </div>

      {/* Header */}
      <Card className="border-primary/20 bg-primary/5">
        <CardHeader className="py-3">
          <CardTitle className="font-display text-lg">Plan your learning goals</CardTitle>
          <CardDescription className="font-body text-xs">
            Rate each goal's priority (1–5 stars) · Reorder, edit, or add goals · Add available materials below
          </CardDescription>
        </CardHeader>
      </Card>

      {/* Available materials */}
      <div className="space-y-1.5">
        <Label className="font-body text-sm font-medium flex items-center gap-1.5">
          <Package className="h-4 w-4 text-muted-foreground" />
          Available materials & equipment
          <span className="text-muted-foreground font-normal">(optional)</span>
        </Label>
        <TagInput
          tags={materialTags}
          onChange={setMaterialTags}
          placeholder="e.g. whiteboard, sticky notes, clickers — press Enter to add"
        />
      </div>

      {/* Goals */}
      <Accordion
        type="multiple"
        defaultValue={goals.map((_, i) => `g-${i}`)}
        className="space-y-2"
      >
        {goals.map((g, i) => (
          <AccordionItem
            key={g.id}
            value={`g-${i}`}
            className={`border rounded-md bg-card overflow-hidden transition-all ${priorityBorderClass(g.priority)}`}
          >
            {/* Toolbar */}
            <div className="flex items-center gap-0.5 px-2 py-0.5 bg-muted/30 border-b border-border/60">
              {/* Star rating */}
              <StarRating
                value={g.priority ?? 0}
                onChange={(p) => setPriority(i, p)}
              />

              <span className="font-body text-xs text-muted-foreground px-1">#{i + 1}</span>
              <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => move(i, -1)} disabled={i === 0}>
                <ArrowUp className="h-3.5 w-3.5" />
              </Button>
              <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => move(i, 1)} disabled={i === goals.length - 1}>
                <ArrowDown className="h-3.5 w-3.5" />
              </Button>
              <div className="ml-auto">
                <Button variant="ghost" size="sm" onClick={() => removeGoal(i)} className="text-destructive gap-1 h-7 text-xs px-2">
                  <Trash2 className="h-3.5 w-3.5" /> Remove
                </Button>
              </div>
            </div>

            <AccordionTrigger className="px-4 py-2 hover:no-underline text-left">
              <div className="font-body text-sm font-medium text-foreground pr-2 whitespace-normal break-words leading-relaxed">
                {g.goal || <span className="italic text-muted-foreground">Untitled goal</span>}
                {(g.priority ?? 0) > 0 && (
                  <span className="ml-2 text-xs text-amber-500 font-normal whitespace-nowrap">
                    {PRIORITY_LABELS[g.priority!]}
                  </span>
                )}
              </div>
            </AccordionTrigger>

            <AccordionContent className="px-4 pb-3 space-y-2">
              {/* Goal textarea */}
              <div className="space-y-1">
                <Label className="font-body text-xs text-muted-foreground">Learning goal</Label>
                <Textarea
                  value={g.goal}
                  onChange={(e) => updateGoal(i, { goal: e.target.value })}
                  rows={2}
                  placeholder="e.g. Apply X to solve Y"
                  className="text-sm"
                />
              </div>

              {/* Collapsible prereqs & activities */}
              <button
                type="button"
                onClick={() => toggleDetails(i)}
                className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground font-body transition-colors"
              >
                <ChevronDown className={`h-3.5 w-3.5 transition-transform ${detailsOpen.has(i) ? "rotate-180" : ""}`} />
                {detailsOpen.has(i) ? "Hide" : "Show"} prerequisites & activities
                {!detailsOpen.has(i) && (
                  <span className="ml-1 text-muted-foreground/60">
                    ({g.prerequisites.length + g.achieveActivities.length + g.assessActivities.length} items)
                  </span>
                )}
              </button>

              {detailsOpen.has(i) && (
                <div className="space-y-2 border-l-2 border-border/40 ml-1 pl-3">
                  <div className="space-y-1">
                    <Label className="font-body text-xs text-muted-foreground">Prerequisite knowledge</Label>
                    <ItemList
                      items={g.prerequisites}
                      onAdd={() => addItem(i, "prerequisites")}
                      onUpdate={(j, v) => updateItem(i, "prerequisites", j, v)}
                      onRemove={(j) => removeItem(i, "prerequisites", j)}
                      placeholder="What learners should already know"
                    />
                  </div>
                  <div className="space-y-1">
                    <Label className="font-body text-xs text-muted-foreground">Activities to achieve the goal</Label>
                    <ItemList
                      items={g.achieveActivities}
                      onAdd={() => addItem(i, "achieveActivities")}
                      onUpdate={(j, v) => updateItem(i, "achieveActivities", j, v)}
                      onRemove={(j) => removeItem(i, "achieveActivities", j)}
                      placeholder="e.g. Pair programming on…"
                    />
                  </div>
                  <div className="space-y-1">
                    <Label className="font-body text-xs text-muted-foreground">Activities to assess the goal</Label>
                    <ItemList
                      items={g.assessActivities}
                      onAdd={() => addItem(i, "assessActivities")}
                      onUpdate={(j, v) => updateItem(i, "assessActivities", j, v)}
                      onRemove={(j) => removeItem(i, "assessActivities", j)}
                      placeholder="e.g. Short quiz, peer feedback…"
                    />
                  </div>
                </div>
              )}
            </AccordionContent>
          </AccordionItem>
        ))}
      </Accordion>

      <Button variant="outline" onClick={addGoal} className="gap-1 w-full">
        <Plus className="h-4 w-4" /> Add another learning goal
      </Button>
    </div>
  );
}
