import React, { useState, useEffect, useCallback, useMemo } from "react";
import { saveAs } from "file-saver";
import { WorkshopSession, WorkshopInput, LearningGoalPlan, ActivityBlock } from "@/lib/workshop-generator";
import { ArrowLeft, Download, CheckCircle2, Presentation, ListChecks, Check, ChevronDown, ChevronUp, Loader2, X, ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { toast } from "@/hooks/use-toast";
import { SlideWorkstation } from "./SlideWorkstation";

interface SlideData {
  title: string;
  subtitle?: string;
  bullets: string[];
  notes?: string;
}

interface Props {
  session: WorkshopSession;
  goals?: LearningGoalPlan[];
  meta: WorkshopInput;
  completedTasks?: string[];
  onUpdateTasks?: (tasks: string[], isAllDone: boolean) => void;
  onBack: () => void;
  onDone: (tasks?: string[]) => void;
}

const phaseEmojis: Record<string, string> = {
  ARRIVE: "👋", ACTIVATE: "💡", INFORM: "🗣️", PROCESS: "🛠️",
  BREAK: "☕", EVALUATE: "✅", SUMMARY: "🏁", LEARNING_CYCLE: "🎯",
};

export default function WorkshopPreparation({ session, goals = [], meta, completedTasks: initialTasks = [], onUpdateTasks, onBack, onDone }: Props) {
  const [isExporting, setIsExporting] = useState(false);
  const [completedTasks, setCompletedTasks] = useState<Set<string>>(new Set(initialTasks));
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  // Cache: keyed by block index, value is fetched slides array
  const [slidesCache, setSlidesCache] = useState<Record<number, SlideData[]>>(session.slides || {});
  const [loadingSlides, setLoadingSlides] = useState<number | null>(null);
  const [openSlideDialog, setOpenSlideDialog] = useState<number | null>(null);
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);
  const [isWorkstationOpen, setIsWorkstationOpen] = useState(false);

  // Keyboard navigation for lightbox
  useEffect(() => {
    if (lightboxIndex === null || openSlideDialog === null) return;
    const slides = slidesCache[openSlideDialog] ?? [];
    const handler = (e: KeyboardEvent) => {
      if (e.key === "ArrowRight") setLightboxIndex(i => i !== null ? Math.min(i + 1, slides.length - 1) : null);
      if (e.key === "ArrowLeft") setLightboxIndex(i => i !== null ? Math.max(i - 1, 0) : null);
      if (e.key === "Escape") setLightboxIndex(null);
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [lightboxIndex, openSlideDialog, slidesCache]);

  const closeLightbox = useCallback(() => setLightboxIndex(null), []);
  const closeDialog = useCallback(() => { setOpenSlideDialog(null); setLightboxIndex(null); }, []);

  const allPrepItems = useMemo(() => {
    return (session?.blocks || []).flatMap((block, i) => {
      const rawMethods = [
        ...(block.methods || []),
        ...(block.sections || []).flatMap(s => s.methods || [])
      ].filter(m => m && !String(m).toLowerCase().includes("lecture") && !String(m).toLowerCase().includes("presentation"));
      const uniqueMethods = Array.from(new Map(rawMethods.map(m => [String(m).toLowerCase().trim(), m])).values());

      const rawMaterials = [
        ...(block.materials || []),
        ...(block.sections || []).flatMap(s => s.materials || [])
      ].filter(Boolean) as string[];
      const uniqueMaterials = Array.from(new Map(rawMaterials.map(m => [String(m).toLowerCase().trim(), m])).values());

      return [
        ...uniqueMaterials.map(m => ({ id: `mat-${i}-${m}`, label: `Prepare: ${m}` })),
        ...uniqueMethods.map(m => ({ id: `meth-${i}-${m}`, label: `Review mechanics: ${m}` })),
        { id: `slide-${i}`, label: `Finalize slides: ${block.phaseLabel}` },
      ];
    });
  }, [session?.blocks]);

  const toggleTask = (taskId: string) => {
    setCompletedTasks(prev => {
      const next = new Set(prev);
      if (next.has(taskId)) next.delete(taskId); else next.add(taskId);
      const isAllDone = allPrepItems.length > 0 && allPrepItems.every((item: any) => next.has(item.id));
      onUpdateTasks?.(Array.from(next), isAllDone);
      return next;
    });
  };

  const toggleCollapsed = (key: string) => {
    setCollapsed(prev => ({ ...prev, [key]: !prev[key] }));
  };

  // Fetch slides for a single block (with cache)
  const fetchBlockSlides = async (blockIndex: number, block: ActivityBlock) => {
    if (slidesCache[blockIndex]) {
      setOpenSlideDialog(blockIndex);
      return;
    }
    setLoadingSlides(blockIndex);
    try {
      const res = await fetch(import.meta.env.BASE_URL + "api/workshop/export/block-slides", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ block, meta }),
      });
      if (!res.ok) throw new Error("Server returned " + res.status);
      const slides: SlideData[] = await res.json();
      setSlidesCache(prev => ({ ...prev, [blockIndex]: slides }));
      setOpenSlideDialog(blockIndex);
    } catch (e) {
      toast({ title: "Slides failed", description: e instanceof Error ? e.message : "Unknown error", variant: "destructive" });
    } finally {
      setLoadingSlides(null);
    }
  };

  // Download whole deck — uses cached slides where available, LLM for the rest
  const downloadDeck = async () => {
    setIsExporting(true);
    toast({ title: "Preparing Deck…", description: "Combining cached slides and generating any missing ones." });
    try {
      // Collect all cached slide arrays in order
      const allCachedSlides = session.blocks.flatMap((_, i) => slidesCache[i] ?? []);
      const hasAllCached = session.blocks.every((_, i) => !!slidesCache[i]);

      let blob: Blob;
      if (hasAllCached && allCachedSlides.length > 0) {
        // Fast path: send pre-built slides, no LLM
        const res = await fetch(import.meta.env.BASE_URL + "api/workshop/export/pptx-assemble", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ session, meta, prebuiltSlides: allCachedSlides }),
        });
        if (!res.ok) throw new Error("Assembly failed");
        blob = await res.blob();
      } else {
        // Slow path: full LLM generation
        const res = await fetch(import.meta.env.BASE_URL + "api/workshop/export/pptx", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ session, meta, goals }),
        });
        if (!res.ok) throw new Error("Generation failed");
        blob = await res.blob();
      }

      const safeName = (session.title || session.learningGoal || "workshop").slice(0, 40).replace(/[^a-z0-9]+/gi, "_").toLowerCase();
      saveAs(blob, `${safeName}_slides.pptx`);
      toast({ title: "Slides Downloaded", description: hasAllCached ? "Assembled from cache (fast!)." : "Generated by AI." });
    } catch (e) {
      toast({ title: "Download failed", description: e instanceof Error ? e.message : "Unknown error", variant: "destructive" });
    } finally {
      setIsExporting(false);
    }
  };

  const downloadPdf = async () => {
    setIsExporting(true);
    toast({ title: "Generating PDF…" });
    try {
      const res = await fetch(import.meta.env.BASE_URL + "api/workshop/export/pdf", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ session, meta, goals }),
      });
      if (!res.ok) throw new Error("Failed to generate PDF");
      const blob = await res.blob();
      const safeName = (session.learningGoal || "workshop").slice(0, 40).replace(/[^a-z0-9]+/gi, "_").toLowerCase();
      saveAs(blob, `${safeName}_session.pdf`);
      toast({ title: "PDF downloaded" });
    } catch (e) {
      toast({ title: "Download failed", description: e instanceof Error ? e.message : "Unknown error", variant: "destructive" });
    } finally {
      setIsExporting(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div>
          <h2 className="font-display font-semibold text-2xl">Class Preparation</h2>
          <p className="text-muted-foreground font-body text-sm mt-1">Review materials, track your prep, and preview slides for each block.</p>
        </div>
        <div className="flex items-center gap-2">
          <Button onClick={downloadPdf} disabled={isExporting} variant="outline" className="gap-2">
            <Download className="h-4 w-4" /> PDF Timetable
          </Button>
          <Button onClick={downloadDeck} disabled={isExporting} className="gap-2">
            <Download className="h-4 w-4" />
            {isExporting ? "Preparing…" : "Download Slide Deck"}
          </Button>
          <Button onClick={() => onDone(Array.from(completedTasks))} className="gap-2 bg-emerald-600 hover:bg-emerald-700 text-white">
            <CheckCircle2 className="h-4 w-4" /> Finish & Save
          </Button>
        </div>
      </div>

      <SlideWorkstation 
        session={session} 
        meta={meta} 
        goals={goals} 
        slidesCache={slidesCache} 
        setSlidesCache={setSlidesCache} 
        onPreview={() => setOpenSlideDialog(-1)} // -1 means all slides
        isOpen={isWorkstationOpen} 
        setIsOpen={setIsWorkstationOpen} 
      />

      <div className="space-y-4">
        {(session?.blocks || []).map((block, i) => {
          const rawMethods = [
            ...(block.methods || []),
            ...(block.sections || []).flatMap(s => s.methods || [])
          ].filter(m => m && !String(m).toLowerCase().includes("lecture") && !String(m).toLowerCase().includes("presentation"));
          const uniqueMethods = Array.from(new Map(rawMethods.map(m => [String(m).toLowerCase().trim(), m])).values());

          const rawMaterials = [
            ...(block.materials || []),
            ...(block.sections || []).flatMap(s => s.materials || [])
          ].filter(Boolean) as string[];
          const uniqueMaterials = Array.from(new Map(rawMaterials.map(m => [String(m).toLowerCase().trim(), m])).values());

          const hasActivities = block.sections?.some(s => s.steps && s.steps.length > 0);

          const prepItems = [
            ...uniqueMaterials.map(m => ({ id: `mat-${i}-${m}`, label: `Prepare: ${m}` })),
            ...uniqueMethods.map(m => ({ id: `meth-${i}-${m}`, label: `Review mechanics: ${m}` })),
            { id: `slide-${i}`, label: `Finalize slides: ${block.phaseLabel}` },
          ];

          const todoItems = prepItems.filter(item => !completedTasks.has(item.id));
          const doneItems = prepItems.filter(item => completedTasks.has(item.id));
          const allDone = prepItems.length > 0 && todoItems.length === 0;
          const blockKey = `block-${i}`;
          const isCollapsed = collapsed[blockKey] ?? false;
          const cachedSlides = slidesCache[i];
          const isLoadingThis = loadingSlides === i;
          const isDialogOpen = openSlideDialog === i;

          return (
            <div
              key={i}
              className={`border rounded-xl shadow-sm transition-all duration-300 overflow-hidden ${allDone ? "border-emerald-400 bg-white dark:bg-zinc-900" : "border-border/60 bg-card"
                }`}
            >
              {/* Collapsible Header */}
              <button
                className="w-full flex items-center gap-3 p-4 text-left hover:bg-muted/30 transition-colors"
                onClick={() => toggleCollapsed(blockKey)}
              >
                <div className={`h-9 w-9 rounded-full flex items-center justify-center text-lg shrink-0 ${allDone ? "bg-emerald-100" : "bg-primary/10"}`}>
                  {phaseEmojis[block.phase] || "✨"}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <h3 className="font-display text-base font-bold leading-tight">{block.phaseLabel}</h3>
                    {allDone && (
                      <span className="text-[10px] font-bold bg-emerald-100 text-emerald-700 px-2 py-0.5 rounded-full border border-emerald-200 uppercase tracking-wide">
                        ✓ All Done
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-muted-foreground">{block.duration} min · {doneItems.length}/{prepItems.length} tasks done</p>
                </div>
                <div className="shrink-0 text-muted-foreground">
                  {isCollapsed ? <ChevronDown className="h-4 w-4" /> : <ChevronUp className="h-4 w-4" />}
                </div>
              </button>

              {/* Expanded Content */}
              {!isCollapsed && (
                <div className="px-4 pb-5 pt-1 grid grid-cols-1 lg:grid-cols-12 gap-5">
                  {/* Kanban (7 cols) */}
                  <div className="lg:col-span-7 grid grid-cols-2 gap-3">
                    {/* To Do */}
                    <div className="bg-muted/20 rounded-lg p-3 border border-border/40 flex flex-col gap-2">
                      <div className="text-[10px] font-bold text-muted-foreground uppercase tracking-wider flex justify-between items-center mb-1">
                        <span>To Do</span>
                        <span className="bg-background border border-border/40 px-1.5 py-0.5 rounded text-[10px]">{todoItems.length}</span>
                      </div>
                      {todoItems.length === 0 ? (
                        <div className="flex-1 flex items-center justify-center py-6">
                          <p className="text-xs text-emerald-600 font-medium">All done! 🎉</p>
                        </div>
                      ) : todoItems.map(item => (
                        <div
                          key={item.id}
                          onClick={() => toggleTask(item.id)}
                          className="bg-background border border-border/60 rounded-md p-2.5 text-xs cursor-pointer hover:border-primary/40 hover:shadow-sm transition-all group flex items-start gap-2"
                        >
                          <div className="mt-[2px] w-3 h-3 rounded-sm border border-muted-foreground/40 group-hover:border-primary/50 flex-shrink-0" />
                          <span className="leading-snug font-body select-none">{item.label}</span>
                        </div>
                      ))}
                    </div>

                    {/* Done */}
                    <div className="bg-muted/10 rounded-lg p-3 border border-border/20 flex flex-col gap-2">
                      <div className="text-[10px] font-bold text-muted-foreground/60 uppercase tracking-wider flex justify-between items-center mb-1">
                        <span>Done</span>
                        <span className="bg-muted/50 border border-border/20 px-1.5 py-0.5 rounded text-[10px]">{doneItems.length}</span>
                      </div>
                      {doneItems.map(item => (
                        <div
                          key={item.id}
                          onClick={() => toggleTask(item.id)}
                          className="bg-background/40 border border-border/30 rounded-md p-2.5 text-xs cursor-pointer hover:bg-background/60 transition-all flex items-start gap-2 opacity-60 hover:opacity-100"
                        >
                          <div className="mt-[2px] w-3 h-3 rounded-sm bg-emerald-500/20 text-emerald-600 flex items-center justify-center flex-shrink-0">
                            <Check className="h-2 w-2" />
                          </div>
                          <span className="leading-snug text-muted-foreground line-through font-body select-none">{item.label}</span>
                        </div>
                      ))}
                    </div>
                  </div>

                  {/* Right: Slide Preview + Activity Script (5 cols) */}
                  <div className="lg:col-span-5 flex flex-col gap-4">
                    {/* Slide Preview Card */}
                    <div className="space-y-1.5">
                      <h4 className="font-display font-semibold flex items-center gap-1.5 text-foreground/80 text-xs uppercase tracking-wide">
                        <Presentation className="h-3.5 w-3.5" /> Integrated Slides
                        {cachedSlides && <span className="text-emerald-600 text-[9px] font-bold bg-emerald-50 px-1.5 py-0.5 rounded border border-emerald-200">CACHED</span>}
                      </h4>
                      {cachedSlides ? (
                        <button
                          onClick={() => setOpenSlideDialog(i)}
                          className="w-full cursor-pointer aspect-video bg-white rounded-lg shadow-sm flex flex-col p-4 text-slate-800 relative group overflow-hidden border border-border/80 text-left transition-all hover:shadow-md hover:border-primary/30"
                        >
                          <div className="absolute top-0 left-0 w-full h-0.5 bg-gradient-to-r from-primary to-primary/50" />
                          <div className="mt-auto relative z-10">
                            <h4 className="text-sm font-display font-bold mb-0.5">{block.phaseLabel}</h4>
                            {block.objective && <p className="text-[10px] text-slate-500 line-clamp-2">{block.objective}</p>}
                          </div>
                          {/* Hover overlay */}
                          <div className="absolute inset-0 bg-white/90 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity z-20 backdrop-blur-[1px]">
                            <div className="flex items-center gap-1.5 text-sm font-medium text-primary">
                              <Presentation className="h-4 w-4" /> View Slides
                            </div>
                          </div>
                        </button>
                      ) : (
                        <div className="w-full aspect-video bg-slate-50/50 rounded-lg border border-dashed border-slate-200 flex flex-col items-center justify-center p-4 text-center">
                          <Presentation className="h-6 w-6 text-slate-300 mb-2" />
                          <p className="text-xs font-medium text-slate-500">To see slides, use the Slide Workstation above.</p>
                          <Button variant="link" size="sm" onClick={() => setIsWorkstationOpen(true)} className="text-xs h-auto p-0 mt-1 text-indigo-600">
                            Open Workstation
                          </Button>
                        </div>
                      )}
                    </div>

                    {/* Activity Script */}
                    {hasActivities && (
                      <div className="space-y-1.5 flex-1 flex flex-col min-h-0">
                        <h4 className="font-display font-semibold flex items-center gap-1.5 text-foreground/80 text-xs uppercase tracking-wide">
                          <ListChecks className="h-3.5 w-3.5" /> Activity Script
                        </h4>
                        <div className="bg-muted/10 border border-border/40 rounded-lg p-3 max-h-[150px] overflow-y-auto flex-1">
                          <ul className="space-y-2">
                            {block.sections!.flatMap(s => s.steps || []).map((step, idx) => {
                              const match = step.match(/^(\d+)\s*(?:min|m)(?:utes?)?\s*(?:—|-|:)\s*(.*)/i);
                              const timeVal = match ? match[1] : "";
                              const contentText = match ? match[2] : step;
                              const prefixMatch = contentText.match(/^(Explain|Prompt|Activity|Summarize|Lecture|Question|Discussion)\s*[:-]\s*(.*)/i);
                              const prefix = prefixMatch ? prefixMatch[1] : "";
                              const mainText = prefixMatch ? prefixMatch[2] : contentText;

                              return (
                                <li key={idx} className="flex items-baseline gap-2 text-xs font-body">
                                  <div className="flex items-center gap-1 shrink-0">
                                    {timeVal && <span className="font-bold text-primary/80 bg-primary/10 px-1 py-0.5 rounded text-[9px]">{timeVal}m</span>}
                                    {prefix && <span className="font-bold text-foreground/70 uppercase tracking-wider text-[9px] bg-background border border-border/50 px-1 py-0.5 rounded">{prefix}</span>}
                                  </div>
                                  <span className="text-muted-foreground leading-relaxed">{mainText}</span>
                                </li>
                              );
                            })}
                          </ul>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Slide Grid Dialog */}
      {openSlideDialog !== null && (openSlideDialog === -1 || slidesCache[openSlideDialog]) && (() => {
        const dialogSlides = openSlideDialog === -1 
          ? session.blocks.flatMap((_, i) => slidesCache[i] ?? [])
          : slidesCache[openSlideDialog] ?? [];
        if (dialogSlides.length === 0) return null;
        
        const dialogTitle = openSlideDialog === -1 ? "All Lecture Slides" : session.blocks[openSlideDialog]?.phaseLabel;

        return (
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/50 backdrop-blur-sm" onClick={closeDialog}>
            <div
              className="bg-white dark:bg-zinc-950 rounded-2xl shadow-2xl border border-border/50 w-full max-w-4xl max-h-[90vh] flex flex-col overflow-hidden"
              onClick={e => e.stopPropagation()}
            >
              <div className="flex items-center justify-between p-5 border-b border-border/50">
                <div>
                  <h2 className="font-display text-xl font-bold">
                    {dialogTitle} — Slide Preview
                  </h2>
                  <p className="text-xs text-muted-foreground mt-0.5">
                    {dialogSlides.length} slides · click a slide to enlarge · use ← → to navigate
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <Button onClick={downloadDeck} size="sm" className="gap-1.5">
                    <Download className="h-3.5 w-3.5" /> Download Deck
                  </Button>
                  <Button variant="ghost" size="icon" onClick={closeDialog}>
                    <X className="h-4 w-4" />
                  </Button>
                </div>
              </div>
              <div className="overflow-y-auto p-5 grid grid-cols-1 md:grid-cols-2 gap-5">
                {dialogSlides.map((slide, idx) => (
                  <button
                    key={idx}
                    onClick={() => setLightboxIndex(idx)}
                    className="relative overflow-hidden min-h-[220px] bg-white border border-slate-200 shadow-sm rounded-lg flex flex-col p-5 text-left cursor-pointer hover:border-primary/50 hover:ring-1 hover:ring-primary/30 hover:shadow-md transition-all group"
                  >
                    <div className="border-b border-slate-100 pb-2.5 mb-3 w-full">
                      <h3 className="text-sm font-bold text-slate-800 leading-snug text-left">{slide.title}</h3>
                      {slide.subtitle && (
                        <p className="text-xs font-semibold text-primary mt-1 text-left">{slide.subtitle}</p>
                      )}
                    </div>
                    <ul className="space-y-2 flex-1 w-full">
                      {slide.bullets?.map((b, j) => (
                        <li key={j} className="text-xs text-slate-600 leading-relaxed flex items-start gap-2">
                          <span className="text-primary/60 mt-0.5 shrink-0">•</span>
                          <span>{b}</span>
                        </li>
                      ))}
                    </ul>
                    {slide.notes && (
                      <div className="mt-3 pt-2 border-t border-slate-100 text-[10px] text-slate-400 italic line-clamp-2">{slide.notes}</div>
                    )}
                    {/* Magnify hint — contained within the button via relative/overflow-hidden */}
                    <div className="absolute inset-0 bg-primary/5 opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center backdrop-blur-[1px]">
                      <span className="bg-white/90 text-primary text-xs font-semibold px-3 py-1.5 rounded-full shadow-sm">Click to Enlarge</span>
                    </div>
                  </button>
                ))}
              </div>
            </div>
          </div>
        );
      })()}


      {/* Floating Slide Genie Button */}
      {!isWorkstationOpen && (
        <Button
          onClick={() => setIsWorkstationOpen(true)}
          className="fixed bottom-6 right-6 h-14 rounded-full shadow-xl bg-indigo-600 hover:bg-indigo-700 text-white gap-2 px-6 z-40 transition-transform hover:scale-105"
        >
          <Presentation className="h-5 w-5" />
          <span className="font-semibold">Slide Genie</span>
        </Button>
      )}

      {/* Lightbox: single slide full-size with arrows */}
      {lightboxIndex !== null && openSlideDialog !== null && (() => {
        const slides = openSlideDialog === -1
          ? session.blocks.flatMap((_, i) => slidesCache[i] ?? [])
          : slidesCache[openSlideDialog] ?? [];
        const slide = slides[lightboxIndex];
        if (!slide) return null;
        const blockLabel = openSlideDialog === -1 ? "Slide Workstation" : session.blocks[openSlideDialog]?.phaseLabel ?? "";
        return (
          <div
            className="fixed inset-0 z-[60] flex items-center justify-center bg-black/80 backdrop-blur-md p-6"
            onClick={closeLightbox}
          >
            {/* Prev arrow */}
            {lightboxIndex > 0 && (
              <button
                className="absolute left-4 top-1/2 -translate-y-1/2 bg-white/10 hover:bg-white/25 transition-colors rounded-full p-3 text-white z-10"
                onClick={e => { e.stopPropagation(); setLightboxIndex(i => i !== null ? i - 1 : null); }}
              >
                <ChevronLeft className="h-6 w-6" />
              </button>
            )}

            {/* Slide card */}
            <div
              className="bg-white rounded-2xl shadow-2xl flex flex-col w-full max-w-3xl aspect-video p-10 relative"
              onClick={e => e.stopPropagation()}
            >
              {/* Top accent */}
              <div className="absolute top-0 left-0 w-full h-1 rounded-t-2xl bg-gradient-to-r from-primary to-primary/40" />
              {/* Block breadcrumb */}
              <p className="text-xs font-semibold text-primary/60 uppercase tracking-widest mb-3">
                {blockLabel} · {lightboxIndex + 1} / {slides.length}
              </p>
              <h2 className="font-display text-2xl font-bold text-slate-800 mb-1 leading-tight">{slide.title}</h2>
              {slide.subtitle && (
                <p className="text-lg font-semibold text-primary mb-5">{slide.subtitle}</p>
              )}
              {!slide.subtitle && <div className="mb-5"></div>}
              <ul className="space-y-3 flex-1">
                {slide.bullets?.map((b, j) => (
                  <li key={j} className="flex items-start gap-3 text-slate-700">
                    <span className="text-primary mt-1 shrink-0">•</span>
                    <span className="text-lg leading-snug">{b}</span>
                  </li>
                ))}
              </ul>
              {slide.notes && (
                <div className="mt-4 pt-3 border-t border-slate-100 text-xs text-slate-400 italic">
                  📝 {slide.notes}
                </div>
              )}
            </div>

            {/* Next arrow */}
            {lightboxIndex < slides.length - 1 && (
              <button
                className="absolute right-4 top-1/2 -translate-y-1/2 bg-white/10 hover:bg-white/25 transition-colors rounded-full p-3 text-white z-10"
                onClick={e => { e.stopPropagation(); setLightboxIndex(i => i !== null ? i + 1 : null); }}
              >
                <ChevronRight className="h-6 w-6" />
              </button>
            )}

            {/* Dot indicators */}
            <div className="absolute bottom-6 left-1/2 -translate-x-1/2 flex gap-1.5">
              {slides.map((_, idx) => (
                <button
                  key={idx}
                  onClick={e => { e.stopPropagation(); setLightboxIndex(idx); }}
                  className={`h-1.5 rounded-full transition-all ${idx === lightboxIndex ? "w-6 bg-white" : "w-1.5 bg-white/40 hover:bg-white/60"
                    }`}
                />
              ))}
            </div>

            {/* Close */}
            <button
              className="absolute top-4 right-4 bg-white/10 hover:bg-white/25 transition-colors rounded-full p-2 text-white"
              onClick={closeLightbox}
            >
              <X className="h-5 w-5" />
            </button>
          </div>
        );
      })()}

      <div className="flex justify-between pt-6 border-t border-border/50">
        <Button variant="outline" onClick={onBack} className="gap-2 font-body">
          <ArrowLeft className="h-4 w-4" /> Previous step
        </Button>
        <Button onClick={() => onDone(Array.from(completedTasks))} className="gap-2 font-body">
          <CheckCircle2 className="h-4 w-4" /> Finish & Save
        </Button>
      </div>


      {/* Floating Slide Genie Button */}
      {!isWorkstationOpen && (
        <Button
          onClick={() => setIsWorkstationOpen(true)}
          className="fixed bottom-6 right-6 h-14 rounded-full shadow-xl bg-indigo-600 hover:bg-indigo-700 text-white gap-2 px-6 z-40 transition-transform hover:scale-105"
        >
          <Presentation className="h-5 w-5" />
          <span className="font-semibold">Slide Genie</span>
        </Button>
      )}
    </div>
  );
}
