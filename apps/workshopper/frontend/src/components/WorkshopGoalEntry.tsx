import { useState, useCallback, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Textarea } from "@/components/ui/textarea";
import { LearningGoalPlan } from "@/lib/workshop-generator";
import { extractPdfText, extractPptxText } from "@/lib/pdf-parser";
import { toast } from "@/hooks/use-toast";
import {
  ArrowLeft, Plus, Trash2, Check, X, Loader2,
  Sparkles, ArrowRight, AlertCircle, Upload, FileText,
} from "lucide-react";

// ── Types ─────────────────────────────────────────────────────────────
interface GoalDraft {
  id: string;
  text: string;
  suggestions: SuggestionItem[];
  checking: boolean;
  dirty: boolean;
  looksGood?: boolean;
}

interface SuggestionItem {
  type: "split" | "refine";
  values: string[];
  message: string;
}

interface Props {
  initialInput: {
    duration?: number;
    participants?: number;
    sessionType?: string;
    studentBackground?: string;
    interactionLevel?: string;
    uploadedMaterialsText?: string;
  };
  onBack: () => void;
  onContinue: (goals: LearningGoalPlan[], uploadedMaterialsText?: string) => void;
  isLoading?: boolean;
  initialGoals?: LearningGoalPlan[];
}

const API_BASE = (import.meta.env.VITE_API_URL as string | undefined) ?? import.meta.env.BASE_URL + "api";

const debounceTimers: Record<string, ReturnType<typeof setTimeout>> = {};

// ── Main component ────────────────────────────────────────────────────
export default function WorkshopGoalEntry({ initialInput, onBack, onContinue, isLoading, initialGoals }: Props) {
  const [goals, setGoals] = useState<GoalDraft[]>(() => {
    if (initialGoals && initialGoals.length > 0) {
      return initialGoals.map((g) => ({
        id: g.id ?? `g${Math.random()}`,
        text: g.goal,
        suggestions: [],
        checking: false,
        dirty: false,
      }));
    }
    return [{ id: "g0", text: "", suggestions: [], checking: false, dirty: false }];
  });
  const [extracting, setExtracting] = useState(false);
  const [uploadingLegacy, setUploadingLegacy] = useState(false);
  const [uploadedText, setUploadedText] = useState<string>(initialInput.uploadedMaterialsText || "");
  const fileInputRef = useRef<HTMLInputElement>(null);
  const legacyInputRef = useRef<HTMLInputElement>(null);

  const updateGoal = useCallback(
    (id: string, patch: Partial<GoalDraft>) =>
      setGoals((prev) => prev.map((g) => (g.id === id ? { ...g, ...patch } : g))),
    []
  );

  const triggerCheck = useCallback(
    async (id: string, text: string) => {
      if (text.trim().length < 15) {
        toast({ title: "Goal too short", description: "Please write a bit more before checking.", variant: "destructive" });
        return;
      }
      updateGoal(id, { checking: true, dirty: false });
      try {
        const res = await fetch(`${API_BASE}/workshop/refine-goal`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            goal: text.trim(),
            context: {
              sessionType: initialInput.sessionType,
              duration: initialInput.duration,
              participants: initialInput.participants,
              studentBackground: initialInput.studentBackground,
            },
          }),
        });
        if (!res.ok) throw new Error("Server error");
        const data: SuggestionItem[] = await res.json();

        if (!data || data.length === 0) {
          updateGoal(id, { suggestions: [], checking: false, looksGood: true });
        } else {
          updateGoal(id, { suggestions: data, checking: false, looksGood: false });
        }
      } catch {
        toast({ title: "Check failed", description: "Could not connect to AI service.", variant: "destructive" });
        updateGoal(id, { checking: false, suggestions: [] });
      }
    },
    [initialInput, updateGoal]
  );

  const handleTextChange = (id: string, value: string) => {
    updateGoal(id, { text: value, dirty: true, suggestions: [], looksGood: false });
  };

  const acceptSuggestion = (goalId: string, suggestion: SuggestionItem) => {
    if (suggestion.type === "refine") {
      updateGoal(goalId, { text: suggestion.values[0], suggestions: [], dirty: false });
    } else {
      setGoals((prev) => {
        const idx = prev.findIndex((g) => g.id === goalId);
        const newGoals = [...prev];
        newGoals[idx] = { ...newGoals[idx], text: suggestion.values[0], suggestions: [], dirty: false };
        newGoals.splice(idx + 1, 0, {
          id: `g${Date.now()}`,
          text: suggestion.values[1],
          suggestions: [],
          checking: false,
          dirty: false,
        });
        return newGoals;
      });
    }
  };

  const dismissSuggestion = (goalId: string, idx: number) =>
    setGoals((prev) =>
      prev.map((g) =>
        g.id === goalId ? { ...g, suggestions: g.suggestions.filter((_, i) => i !== idx) } : g
      )
    );

  const addGoal = () =>
    setGoals((prev) => [
      ...prev,
      { id: `g${Date.now()}`, text: "", suggestions: [], checking: false, dirty: false },
    ]);

  const removeGoal = (id: string) => setGoals((prev) => prev.filter((g) => g.id !== id));

  // ── File upload: extract goals from document ──────────────────────
  const handleFileUpload = async (file: File | undefined) => {
    if (!file) return;
    const allowed = ["application/pdf", "text/plain"];
    if (!allowed.includes(file.type)) {
      toast({ title: "Unsupported file", description: "Please upload a PDF or plain-text (.txt) file.", variant: "destructive" });
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      toast({ title: "File too large", description: "File must be under 10 MB.", variant: "destructive" });
      return;
    }
    setExtracting(true);
    try {
      let text: string;
      if (file.type === "application/pdf") {
        text = await extractPdfText(file);
      } else {
        text = await file.text();
      }
      if (!text || text.trim().length < 20) {
        toast({ title: "Could not read file", description: "No readable text found in the file.", variant: "destructive" });
        return;
      }

      const res = await fetch(`${API_BASE}/workshop/extract-goals`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          documentText: text.slice(0, 15000),
          context: {
            sessionType: initialInput.sessionType,
            duration: initialInput.duration,
            participants: initialInput.participants,
            studentBackground: initialInput.studentBackground,
          },
        }),
      });
      if (!res.ok) throw new Error(await res.text());
      const extracted: string[] = await res.json();

      if (!extracted || extracted.length === 0) {
        toast({ title: "No goals found", description: "The assistant couldn't find learning goals in the document.", variant: "destructive" });
        return;
      }

      const newDrafts: GoalDraft[] = extracted.map((t, i) => ({
        id: `gx-${Date.now()}-${i}`,
        text: t,
        suggestions: [],
        checking: false,
        dirty: false,
      }));

      setGoals((prev) => {
        const nonEmpty = prev.filter((g) => g.text.trim().length > 0);
        const merged = [...nonEmpty, ...newDrafts];
        // Note: previously we auto-checked here, now it's manual
        return merged.length > 0 ? merged : [{ id: "g0", text: "", suggestions: [], checking: false, dirty: false }];
      });

      toast({
        title: `${extracted.length} goal${extracted.length > 1 ? "s" : ""} extracted`,
        description: "Review the suggestions and refine them as needed.",
      });
    } catch (err) {
      toast({
        title: "Extraction failed",
        description: err instanceof Error ? err.message : "Unknown error",
        variant: "destructive",
      });
    } finally {
      setExtracting(false);
      if (fileInputRef.current) fileInputRef.current.value = "";
    }
  };

  // ── File upload: legacy materials for slide generation ────────────────
  const handleLegacyMaterialUpload = async (file: File | undefined) => {
    if (!file) return;
    const allowed = ["application/pdf", "text/plain", "application/vnd.openxmlformats-officedocument.presentationml.presentation"];
    if (!allowed.includes(file.type) && !file.name.endsWith(".pptx")) {
      toast({ title: "Unsupported file", description: "Please upload a PDF, PPTX, or plain-text (.txt) file.", variant: "destructive" });
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      toast({ title: "File too large", description: "File must be under 10 MB.", variant: "destructive" });
      return;
    }
    setUploadingLegacy(true);
    try {
      let text: string;
      if (file.type === "application/pdf") {
        text = await extractPdfText(file);
      } else if (file.name.endsWith(".pptx") || file.type.includes("presentationml")) {
        text = await extractPptxText(file);
      } else {
        text = await file.text();
      }
      if (!text || text.trim().length < 20) {
        toast({ title: "Could not read file", description: "No readable text found in the file.", variant: "destructive" });
        return;
      }
      setUploadedText(text.slice(0, 15000));
      toast({ title: "Materials uploaded", description: "Successfully extracted text from the document." });
    } catch (err) {
      toast({
        title: "Upload failed",
        description: err instanceof Error ? err.message : "Unknown error",
        variant: "destructive",
      });
    } finally {
      setUploadingLegacy(false);
      if (legacyInputRef.current) legacyInputRef.current.value = "";
    }
  };

  const [isFixingGrammar, setIsFixingGrammar] = useState(false);

  const handleContinue = async () => {
    const validGoals = goals.filter((g) => g.text.trim().length > 0);
    if (validGoals.length === 0) return;

    setIsFixingGrammar(true);
    let finalGoalsText = validGoals.map((g) => g.text.trim());
    try {
      const res = await fetch(`${API_BASE}/workshop/fix-goals-grammar`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(finalGoalsText),
      });
      if (res.ok) {
        const fixed = await res.json();
        if (Array.isArray(fixed) && fixed.length === finalGoalsText.length) {
          finalGoalsText = fixed;
        }
      }
    } catch (e) {
      console.error("Grammar check failed", e);
    } finally {
      setIsFixingGrammar(false);
    }

    const plans: LearningGoalPlan[] = finalGoalsText.map((text, i) => ({
      id: validGoals[i]?.id || `g${i}`,
      goal: text,
      originalGoal: text,
      prerequisites: [],
      achieveActivities: [],
      assessActivities: [],
      priority: 0,
    }));
    onContinue(plans, uploadedText);
  };

  const canContinue = goals.some((g) => g.text.trim().length > 5) && !isLoading && !extracting;

  return (
    <div className="space-y-4 pb-20">
      <Card className="border-border/60 shadow-lg relative overflow-visible flex flex-col">
        <CardHeader className="py-4">
          <CardTitle className="font-display text-3xl flex items-center gap-2">
            <Sparkles className="h-5 w-5 text-primary" />
            Step 3: Enter Learning Goals
          </CardTitle>
          <CardDescription className="font-body text-sm">
            Type each goal, or upload a document to extract them automatically.
            The assistant will give real-time suggestions — accept or dismiss any feedback as you go.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-5">

          {/* ── File upload strip ─────────────────────────────────── */}
          <div className="flex items-center gap-3 rounded-lg border border-dashed border-border/70 bg-muted/20 px-4 py-3">
            <Upload className="h-4 w-4 text-muted-foreground shrink-0" />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-body text-foreground font-medium">Import Learning Goals</p>
              <p className="text-xs text-muted-foreground">PDF or TXT — goals are extracted automatically</p>
            </div>
            {extracting ? (
              <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <Loader2 className="h-3.5 w-3.5 animate-spin" /> Extracting…
              </div>
            ) : (
              <label className="cursor-pointer">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="gap-1.5 pointer-events-none"
                  disabled={extracting}
                  asChild
                >
                  <span>
                    <Sparkles className="h-3.5 w-3.5" /> Extract Goals
                  </span>
                </Button>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="application/pdf,text/plain"
                  className="hidden"
                  onChange={(e) => handleFileUpload(e.target.files?.[0])}
                  disabled={extracting}
                />
              </label>
            )}
          </div>

          {/* ── File upload strip: Legacy Materials ─────────────────── */}
          <div className="flex items-center gap-3 rounded-lg border border-dashed border-border/70 bg-muted/10 px-4 py-3">
            <FileText className="h-4 w-4 text-muted-foreground shrink-0" />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-body text-foreground font-medium">Upload Legacy Materials</p>
              <p className="text-xs text-muted-foreground">PDF or PPTX — text will be used to tailor slide content</p>
            </div>
            {uploadingLegacy ? (
              <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                <Loader2 className="h-3.5 w-3.5 animate-spin" /> Uploading…
              </div>
            ) : (
              <label className="cursor-pointer">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="gap-1.5 pointer-events-none"
                  disabled={uploadingLegacy}
                  asChild
                >
                  <span>
                    <Upload className="h-3.5 w-3.5" /> Upload Materials
                  </span>
                </Button>
                <input
                  ref={legacyInputRef}
                  type="file"
                  accept="application/pdf,text/plain,.pptx"
                  className="hidden"
                  onChange={(e) => handleLegacyMaterialUpload(e.target.files?.[0])}
                  disabled={uploadingLegacy}
                />
              </label>
            )}
            {uploadedText && (
              <div className="flex items-center gap-2 text-sm text-emerald-600 bg-emerald-50 px-3 py-1.5 rounded-md ml-auto">
                <Check className="h-4 w-4" /> Uploaded successfully
                <button type="button" onClick={() => setUploadedText("")} className="hover:text-emerald-800 ml-2">
                  <X className="h-3 w-3" />
                </button>
              </div>
            )}
          </div>

          <div className="pt-2 border-t border-border/40">
            <h3 className="font-display text-lg font-medium text-foreground mb-3">
              Participants will be able to...
            </h3>
            <div className="space-y-4">
              {goals.map((g, idx) => (
                <div key={g.id} className="space-y-2">
                  {/* Header row */}
                  <div className="flex gap-2 items-start">
                    <div className="flex-1 relative">
                      <Textarea
                        placeholder="e.g. apply logistic regression to classification problems…"
                        value={g.text}
                        onChange={(e) => handleTextChange(g.id, e.target.value)}
                        rows={2}
                        className="resize-none h-[60px] min-h-[60px] font-body text-sm leading-relaxed"
                        disabled={isLoading}
                      />
                      {g.checking && (
                        <div className="absolute bottom-2 right-2 flex items-center gap-1 text-[10px] text-muted-foreground">
                          <Loader2 className="h-3 w-3 animate-spin" /> checking…
                        </div>
                      )}
                    </div>
                    <div className="flex flex-col items-stretch gap-1 shrink-0 pt-1">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => triggerCheck(g.id, g.text)}
                        className="h-7 px-2.5 text-xs text-primary hover:text-primary hover:bg-primary/10 border-primary/20 gap-1.5"
                        disabled={g.checking || isLoading}
                        title="Check with AI"
                      >
                        <Sparkles className="h-3 w-3" />
                        Refine with AI
                      </Button>
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        onClick={() => removeGoal(g.id)}
                        className="h-7 px-2.5 text-xs text-destructive gap-1.5 hover:bg-destructive/10 hover:text-destructive"
                        disabled={goals.length === 1 || isLoading}
                      >
                        <Trash2 className="h-3 w-3" />
                        Remove
                      </Button>
                    </div>
                  </div>

                  {/* AI suggestions */}
                  {g.looksGood && g.suggestions.length === 0 && !g.checking && (
                    <div className="rounded-lg border border-emerald-400/30 bg-emerald-500/5 px-3 py-2.5 text-sm flex items-center gap-2">
                      <Check className="h-3.5 w-3.5 text-emerald-500 shrink-0" />
                      <p className="text-xs text-emerald-700 dark:text-emerald-400 leading-relaxed">
                        Looks good! The AI didn't find any issues with this learning goal.
                      </p>
                    </div>
                  )}
                  {g.suggestions.map((s, sIdx) => (
                    <div
                      key={sIdx}
                      className={`rounded-lg border px-3 py-2.5 text-sm space-y-1.5 ${s.type === "split"
                          ? "border-blue-400/30 bg-blue-500/5"
                          : "border-amber-400/30 bg-amber-500/5"
                        }`}
                    >
                      <div className="flex items-start gap-1.5">
                        <AlertCircle
                          className={`h-3.5 w-3.5 mt-0.5 shrink-0 ${s.type === "split" ? "text-blue-500" : "text-amber-500"
                            }`}
                        />
                        <p className="text-xs text-muted-foreground leading-relaxed">{s.message}</p>
                      </div>
                      {s.values.map((v, vi) => (
                        <p key={vi} className="text-xs font-medium text-foreground ml-5 italic">
                          {s.type === "split" ? `Goal ${vi + 1}: ` : "→ "}
                          {v}
                        </p>
                      ))}
                      <div className="flex gap-2 ml-5 mt-1">
                        <Button
                          size="sm"
                          variant="outline"
                          className="h-6 text-xs gap-1 border-green-500/30 text-green-700 hover:bg-green-500/10"
                          onClick={() => acceptSuggestion(g.id, s)}
                        >
                          <Check className="h-3 w-3" /> Accept
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          className="h-6 text-xs gap-1 text-muted-foreground"
                          onClick={() => dismissSuggestion(g.id, sIdx)}
                        >
                          <X className="h-3 w-3" /> Dismiss
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              ))}
            </div>
          </div>

          <Button
            type="button"
            variant="outline"
            onClick={addGoal}
            className="gap-2 w-full border-dashed"
            disabled={isLoading || extracting}
          >
            <Plus className="h-4 w-4" /> Add another learning goal
          </Button>

        </CardContent>

        {/* Floating Footer Bar */}
        <div className="sticky bottom-4 z-30 mx-4 mb-4 rounded-xl border border-border/80 bg-white/95 dark:bg-zinc-900/95 backdrop-blur-md shadow-2xl p-3 flex items-center justify-between gap-4 transition-all">
          <Button variant="outline" size="sm" onClick={onBack} className="gap-2 font-body shrink-0" disabled={isLoading || extracting}>
            <ArrowLeft className="h-4 w-4" /> Previous
          </Button>
          <Button onClick={handleContinue} disabled={!canContinue || isFixingGrammar} size="sm" className="px-6 gap-2 shadow-md hover:shadow-lg transition-shadow shrink-0">
            {isFixingGrammar ? (
              <><Loader2 className="h-3.5 w-3.5 animate-spin" /> Checking…</>
            ) : (
              <>Continue <ArrowRight className="h-3.5 w-3.5" /></>
            )}
          </Button>
        </div>
      </Card>
    </div>
  );
}
