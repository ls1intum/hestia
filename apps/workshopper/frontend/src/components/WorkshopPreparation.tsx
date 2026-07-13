import React, { useState, useEffect, useCallback, useMemo } from "react";
import { saveAs } from "file-saver";
import { WorkshopSession, WorkshopInput, LearningGoalPlan, ActivityBlock, SlideData } from "@/lib/workshop-generator";
import { ArrowLeft, ArrowRight, Download, CheckCircle2, Presentation, ListChecks, Check, ChevronDown, ChevronUp, Loader2, X, ChevronLeft, ChevronRight, Info } from "lucide-react";
import { Button } from "@/components/ui/button";
import { toast } from "@/hooks/use-toast";
import { SlideWorkstation } from "./SlideWorkstation";

interface Props {
  session: WorkshopSession;
  goals?: LearningGoalPlan[];
  meta: WorkshopInput;
  completedTasks?: string[];
  slidesCache: Record<number, SlideData[]>;
  setSlidesCache: React.Dispatch<React.SetStateAction<Record<number, SlideData[]>>>;
  onUpdateTasks?: (tasks: string[], isAllDone: boolean) => void;
  onBack: () => void;
  onDone: (tasks?: string[]) => void;
}

const phaseEmojis: Record<string, string> = {
  ARRIVE: "👋", ACTIVATE: "💡", INFORM: "🗣️", PROCESS: "🛠️",
  BREAK: "☕", EVALUATE: "✅", SUMMARY: "🏁", LEARNING_CYCLE: "🎯",
};

export default function WorkshopPreparation({ session, goals = [], meta, completedTasks: initialTasks = [], slidesCache, setSlidesCache, onUpdateTasks, onBack, onDone }: Props) {
  const [completedTasks, setCompletedTasks] = useState<Set<string>>(new Set(initialTasks));
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [loadingSlides, setLoadingSlides] = useState<number | null>(null);
  const [openSlideDialog, setOpenSlideDialog] = useState<number | null>(null);
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);
  const [showMechanics, setShowMechanics] = useState(false);
  const [isKanbanOpen, setIsKanbanOpen] = useState(false);
  const [isSlideOpen, setIsSlideOpen] = useState(false);

  const getMechanicDescription = (mechanic: string) => {
    const m = String(mechanic).toLowerCase();
    if (m.includes("think-pair-share") || m.includes("think pair share")) return "A three-step structure: individuals first reflect alone, then discuss with a partner, then share with the wider group. Balances independent thinking with collaborative discussion.";
    if (m.includes("brainstorm")) return "A free-flowing idea-generation session where quantity and creativity are prioritized over immediate judgment. Useful for problem-solving and innovation.";
    if (m.includes("role play") || m.includes("role-play")) return "Participants act out defined roles or scenarios to practice skills, explore perspectives, or simulate real-life interactions in a low-stakes environment. Great for building empathy and interpersonal skills.";
    if (m.includes("case study")) return "An in-depth analysis of a real or realistic scenario, where participants examine context, decisions, and outcomes to extract practical lessons. Ideal for applying theory to real-world situations.";
    if (m.includes("group discussion") || m.includes("discussion")) return "An open conversation among participants to explore a topic collaboratively, share perspectives, and build on each other's ideas. Best for surfacing diverse viewpoints and encouraging active listening.";
    if (m.includes("hands-on practice") || m.includes("practice")) return "A guided, practical exercise where participants directly apply a skill or tool themselves rather than just observing. Reinforces learning through doing and immediate feedback.";
    if (m.includes("quiz") || m.includes("poll")) return "Short, structured questions used to check understanding, gather opinions, or gauge the room in real time. Quick to run and useful for engagement or knowledge checks.";
    if (m.includes("peer review") || m.includes("peer feedback")) return "Participants evaluate and give constructive feedback on each other's work. Builds critical thinking and exposes people to different approaches and standards.";
    if (m.includes("q&a") || m.includes("questions") || m.includes("q & a")) return "A dedicated segment where participants can ask questions and receive direct answers from a facilitator or expert. Clarifies doubts and encourages open dialogue.";
    if (m.includes("world cafe")) return "A structured conversational process where participants rotate between small table groups to discuss different aspects of a central theme.";
    if (m.includes("jigsaw")) return "Participants become experts in one aspect of a topic in a specialized group, then re-form into new groups to teach their peers what they learned.";
    if (m.includes("fishbowl")) return "A small group discusses a topic in a central circle while the rest of the participants observe from an outer circle, occasionally swapping places.";
    if (m.includes("gallery walk")) return "Participants walk around the room to view, discuss, and add comments to charts or posters created by different groups.";
    if (m.includes("icebreaker")) return "A short, interactive activity designed to help participants get to know each other and feel comfortable in the group.";
    if (m.includes("snowball")) return "Individuals pair up to discuss an idea, then join another pair to form a group of four, continuing to double in size to synthesize ideas.";
    return "A structured pedagogical activity designed to engage participants, encourage interaction, and facilitate active learning of the workshop material.";
  };

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
    const rawMethods: string[] = [];
    const rawMaterials: string[] = [];

    (session?.blocks || []).forEach(block => {
      rawMethods.push(
        ...(block.methods || []),
        ...(block.sections || []).flatMap(s => s.methods || [])
      );
      rawMaterials.push(
        ...(block.materials || []) as string[],
        ...(block.sections || []).flatMap(s => s.materials || [])
      );
    });

    const uniqueMethods = Array.from(new Map(rawMethods
      .filter(m => m && !String(m).toLowerCase().includes("lecture") && !String(m).toLowerCase().includes("presentation"))
      .map(m => [String(m).toLowerCase().trim(), m])
    ).values());

    const uniqueMaterials = Array.from(new Map(rawMaterials
      .filter(Boolean)
      .map(m => [String(m).toLowerCase().trim(), m])
    ).values());

    const baseItems = [
      { id: 'slide-all', label: 'Finalize all slides' }
    ];

    if (uniqueMaterials.length > 0) {
      baseItems.push({
        id: 'mat-all',
        label: `Prepare all materials: ${uniqueMaterials.join(', ')}`
      });
    }

    if (uniqueMethods.length > 0) {
      baseItems.push({
        id: 'meth-all',
        label: `Review all mechanics: ${uniqueMethods.join(', ')}`,
        methods: uniqueMethods
      } as any);
    }

    return baseItems;
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

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div>
          <h2 className="font-display font-semibold text-2xl">Class Preparation</h2>
          <p className="text-muted-foreground font-body text-sm mt-1">Review materials, track your prep, and preview slides for each block.</p>
        </div>
      </div>

      <SlideWorkstation
        session={session}
        meta={meta}
        goals={goals}
        slidesCache={slidesCache}
        setSlidesCache={setSlidesCache}
        onPreview={() => setOpenSlideDialog(-1)} // -1 means all slides
        isOpen={isSlideOpen}
        setIsOpen={setIsSlideOpen}
      />

      <div className="space-y-4">
        {(() => {
          const todoItems = allPrepItems.filter(item => !completedTasks.has(item.id));
          const doneItems = allPrepItems.filter(item => completedTasks.has(item.id));
          const allDone = allPrepItems.length > 0 && todoItems.length === 0;

          return (
            <div className={`border rounded-xl shadow-sm transition-all duration-300 overflow-hidden ${allDone ? "border-emerald-400 bg-white dark:bg-zinc-900" : "border-border/60 bg-card"}`}>
              {/* Kanban Header */}
              <button
                onClick={() => setIsKanbanOpen(!isKanbanOpen)}
                className="w-full flex items-center gap-3 p-4 text-left border-b border-border/40 bg-transparent hover:bg-muted/10 transition-colors"
              >
                <div className={`h-9 w-9 rounded-full flex items-center justify-center text-lg shrink-0 ${allDone ? "bg-emerald-100" : "bg-primary/10"}`}>
                  <ListChecks className={`h-5 w-5 ${allDone ? "text-emerald-600" : "text-primary"}`} />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <h3 className="font-display text-base font-bold leading-tight">Session Preparation Checklist</h3>
                    {allDone && (
                      <span className="text-[10px] font-bold bg-emerald-100 text-emerald-700 px-2 py-0.5 rounded-full border border-emerald-200 uppercase tracking-wide">
                        ✓ All Done
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-muted-foreground mt-0.5">{doneItems.length}/{allPrepItems.length} tasks completed</p>
                </div>
                <div className="text-muted-foreground">
                  {isKanbanOpen ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                </div>
              </button>

              {/* Kanban Body */}
              {isKanbanOpen && (
                <div className="p-5 grid grid-cols-1 md:grid-cols-2 gap-6 animate-in slide-in-from-top-2 duration-200">
                  {/* To Do */}
                  <div className="bg-muted/10 rounded-xl p-4 border border-border/20 flex flex-col gap-3">
                    <div className="text-[11px] font-bold text-muted-foreground uppercase tracking-wider flex justify-between items-center mb-1">
                      <span>To Do</span>
                      <span className="bg-background border border-border/40 px-2 py-0.5 rounded text-[11px]">{todoItems.length}</span>
                    </div>
                    {todoItems.length === 0 ? (
                      <div className="flex-1 flex flex-col items-center justify-center py-12 text-center">
                        <div className="h-12 w-12 rounded-full bg-emerald-100 flex items-center justify-center mb-3">
                          <Check className="h-6 w-6 text-emerald-600" />
                        </div>
                        <p className="text-sm text-emerald-700 font-bold">You're all set! 🎉</p>
                        <p className="text-xs text-emerald-600/70 mt-1">All preparation tasks are complete.</p>
                      </div>
                    ) : (
                      <div className="space-y-2.5">
                        {todoItems.map(item => (
                          <div
                            key={item.id}
                            className="bg-background border border-border/60 rounded-lg p-3 text-sm hover:border-primary/40 hover:shadow-sm transition-all group flex flex-col gap-2 shadow-sm"
                          >
                            <div className="flex items-start gap-3">
                              <div
                                onClick={() => toggleTask(item.id)}
                                className="cursor-pointer mt-0.5 w-4 h-4 rounded border border-muted-foreground/40 group-hover:border-primary/50 flex-shrink-0 flex items-center justify-center"
                              />
                              <div className="flex-1 flex flex-col min-w-0">
                                <div className="flex items-start justify-between gap-2">
                                  <span
                                    onClick={() => toggleTask(item.id)}
                                    className="cursor-pointer leading-snug font-body select-none text-foreground font-medium mt-0.5"
                                  >
                                    {item.label}
                                  </span>
                                  {item.id === 'meth-all' && (
                                    <button
                                      onClick={(e) => { e.stopPropagation(); setShowMechanics(!showMechanics); }}
                                      className={`shrink-0 transition-colors p-1.5 rounded-md hover:bg-muted ${showMechanics ? "text-primary bg-primary/10" : "text-muted-foreground hover:text-foreground"}`}
                                      title="Show detailed mechanics"
                                    >
                                      <Info className="h-4 w-4" />
                                    </button>
                                  )}
                                </div>
                                {item.id === 'slide-all' && (
                                  <p className="text-[11px] text-foreground/80 mt-1 font-body leading-relaxed">
                                    You can incorporate the generated slides into your own lecture slides or vice versa.
                                  </p>
                                )}
                              </div>
                            </div>
                            {item.id === 'meth-all' && showMechanics && (
                              <div className="mt-2 p-3 bg-muted/30 rounded-md border border-border/50 space-y-2.5 animate-in fade-in slide-in-from-top-1 duration-200">
                                <h4 className="text-[10px] font-bold text-muted-foreground uppercase tracking-wider">Detailed Mechanics</h4>
                                {((item as any).methods || []).map((m: string, idx: number) => (
                                  <div key={idx} className="text-xs font-body leading-relaxed">
                                    <span className="font-bold text-foreground/80">{m}:</span>{" "}
                                    <span className="text-muted-foreground">{getMechanicDescription(m)}</span>
                                  </div>
                                ))}
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    )}
                  </div>

                  {/* Done */}
                  <div className="bg-muted/10 rounded-xl p-4 border border-border/20 flex flex-col gap-3">
                    <div className="text-[11px] font-bold text-muted-foreground/60 uppercase tracking-wider flex justify-between items-center mb-1">
                      <span>Done</span>
                      <span className="bg-muted/50 border border-border/20 px-2 py-0.5 rounded text-[11px]">{doneItems.length}</span>
                    </div>
                    {doneItems.length === 0 ? (
                      <div className="flex-1 flex items-center justify-center py-8">
                        <p className="text-sm text-muted-foreground/50 italic">Completed tasks will appear here</p>
                      </div>
                    ) : (
                      <div className="space-y-2.5">
                        {doneItems.map(item => (
                          <div
                            key={item.id}
                            className="bg-background border border-border/60 rounded-lg p-3 text-sm hover:border-primary/40 transition-all flex flex-col gap-2 opacity-80 hover:opacity-100 group shadow-sm"
                          >
                            <div className="flex items-start gap-3">
                              <div
                                onClick={() => toggleTask(item.id)}
                                className="cursor-pointer mt-0.5 w-4 h-4 rounded bg-emerald-500/20 text-emerald-600 flex items-center justify-center flex-shrink-0"
                              >
                                <Check className="h-3 w-3" />
                              </div>
                              <div className="flex-1 flex flex-col min-w-0">
                                <div className="flex items-start justify-between gap-2">
                                  <span
                                    onClick={() => toggleTask(item.id)}
                                    className="cursor-pointer leading-snug text-foreground font-medium font-body select-none mt-0.5"
                                  >
                                    {item.label}
                                  </span>
                                  {item.id === 'meth-all' && (
                                    <button
                                      onClick={(e) => { e.stopPropagation(); setShowMechanics(!showMechanics); }}
                                      className={`shrink-0 transition-colors p-1.5 rounded-md hover:bg-muted ${showMechanics ? "text-primary bg-primary/10" : "text-muted-foreground hover:text-foreground"}`}
                                      title="Show detailed mechanics"
                                    >
                                      <Info className="h-4 w-4" />
                                    </button>
                                  )}
                                </div>
                                {item.id === 'slide-all' && (
                                  <p className="text-[11px] text-foreground/80 mt-1 font-body leading-relaxed">
                                    You can incorporate the generated slides into your own lecture slides or vice versa.
                                  </p>
                                )}
                              </div>
                            </div>
                            {item.id === 'meth-all' && showMechanics && (
                              <div className="mt-2 p-3 bg-muted/20 rounded-md border border-border/30 space-y-2.5 animate-in fade-in slide-in-from-top-1 duration-200 opacity-80">
                                <h4 className="text-[10px] font-bold text-muted-foreground uppercase tracking-wider">Detailed Mechanics</h4>
                                {((item as any).methods || []).map((m: string, idx: number) => (
                                  <div key={idx} className="text-xs font-body leading-relaxed">
                                    <span className="font-bold text-foreground/60">{m}:</span>{" "}
                                    <span className="text-muted-foreground/80">{getMechanicDescription(m)}</span>
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
              )}
            </div>
          );
        })()}
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
          Continue to Final Review <ArrowRight className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}
