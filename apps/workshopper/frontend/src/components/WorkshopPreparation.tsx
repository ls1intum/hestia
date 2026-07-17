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

import { phaseEmojis, getMechanicDescription } from "@/lib/constants";

export default function WorkshopPreparation({ session, goals = [], meta, completedTasks: initialTasks = [], slidesCache, setSlidesCache, onUpdateTasks, onBack, onDone }: Props) {
  const [completedTasks, setCompletedTasks] = useState<Set<string>>(new Set(initialTasks));
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const [loadingSlides, setLoadingSlides] = useState<number | null>(null);
  const [openSlideDialog, setOpenSlideDialog] = useState<number | null>(null);
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);
  const [showMechanics, setShowMechanics] = useState(false);
  const [isKanbanOpen, setIsKanbanOpen] = useState(false);
  const [isSlideOpen, setIsSlideOpen] = useState(false);

  // getMechanicDescription imported from constants

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
    if (!session?.blocks) return [];
    
    const baseItems: any[] = [];
    const contentTasks: string[] = [];
    const activityTasks: string[] = [];
    const materialTasks: string[] = [];
    
    session.blocks.forEach((block, index) => {
        const phase = (block.phase || "").toUpperCase();
        const labelUpper = (block.phaseLabel || block.phase || "").toUpperCase();
        const label = block.phaseLabel || block.phase || `Block ${index + 1}`;
        
        const isIntro = phase.includes("WELCOME") || phase.includes("SETUP") || phase.includes("INTRODUCTION") || phase === "INTRO" || phase === "ARRIVE" || labelUpper.includes("WELCOME") || labelUpper.includes("SETUP") || labelUpper.includes("INTRODUCTION") || labelUpper.includes("ARRIVE");
        const isClosing = phase.includes("WRAP") || phase.includes("CLOSING") || phase.includes("CONCLUSION") || phase.includes("SUMMARY") || labelUpper.includes("WRAP") || labelUpper.includes("CLOSING") || labelUpper.includes("CONCLUSION") || labelUpper.includes("SUMMARY");
        const isCheck = phase.includes("CHECK") || labelUpper.includes("CHECK");
        const isActivity = phase.includes("LEARNING") || phase.includes("CYCLE") || phase.includes("ACTIVATE") || phase === "CUSTOM" || isCheck || labelUpper.includes("LEARNING") || labelUpper.includes("CYCLE") || labelUpper.includes("ACTIVATE");

        if (isIntro) {
             contentTasks.push(`"${label}" (Welcome/Setup slides)`);
        }
        else if (isClosing) {
             contentTasks.push(`"${label}" (Closing/Summary slides)`);
        }
        else if (isActivity) {
             if (isCheck) {
                 activityTasks.push(`"${label}" (Quiz/Poll Questions)`);
                 activityTasks.push(`"${label}" (Check debrief/summary)`);
             } else {
                 contentTasks.push(`"${label}" (Lecture Content)`);
                 activityTasks.push(`"${label}" (Prompts and Debriefs)`);
             }
        }
        
        const materials = [...(block.materials || []), ...(block.sections || []).flatMap(s => s.materials || [])]
            .filter(Boolean)
            .map(m => String(m).trim())
            .filter(m => {
                const lower = m.toLowerCase();
                return !lower.includes("slide") && !lower.includes("prompt card");
            });
        const uniqueMaterials = Array.from(new Set(materials));
        if (uniqueMaterials.length > 0) {
             materialTasks.push(`"${label}": ${uniqueMaterials.join(', ')}`);
        }
    });

    if (contentTasks.length > 0) {
        baseItems.unshift({
            id: 'all-content',
            label: 'Replace all placeholder slides with your own lecture and intro/closing content',
            category: 'content',
            subTodos: contentTasks.map((task, i) => ({ id: `cnt-sub-${i}`, label: task }))
        });
    }

    if (activityTasks.length > 0) {
        baseItems.push({
            id: 'all-activities',
            label: 'Review and finalize all activity prompts, quizzes, and debriefs',
            category: 'activity',
            subTodos: activityTasks.map((task, i) => ({ id: `act-sub-${i}`, label: task }))
        });
    }

    if (materialTasks.length > 0) {
        baseItems.push({
            id: 'all-materials',
            label: 'Prepare all additional materials for the session',
            category: 'prep',
            subTodos: materialTasks.map((task, i) => ({ id: `mat-sub-${i}`, label: task }))
        });
    }

    return baseItems;
  }, [session?.blocks]);

  const toggleTask = (taskId: string) => {
    setCompletedTasks(prev => {
      const next = new Set(prev);
      const item = allPrepItems.find((i: any) => i.id === taskId);
      
      if (next.has(taskId)) {
        next.delete(taskId);
        if (item && item.subTodos) item.subTodos.forEach((sub: any) => next.delete(sub.id));
      } else {
        next.add(taskId);
        if (item && item.subTodos) item.subTodos.forEach((sub: any) => next.add(sub.id));
      }
      
      const isAllDone = allPrepItems.length > 0 && allPrepItems.every((item: any) => next.has(item.id));
      onUpdateTasks?.(Array.from(next), isAllDone);
      return next;
    });
  };

  const toggleSubTask = (parentId: string, subId: string) => {
    setCompletedTasks(prev => {
      const next = new Set(prev);
      if (next.has(subId)) next.delete(subId); else next.add(subId);
      
      const item = allPrepItems.find((i: any) => i.id === parentId);
      if (item && item.subTodos) {
        const allSubDone = item.subTodos.every((sub: any) => next.has(sub.id));
        if (allSubDone) next.add(parentId);
        else next.delete(parentId);
      }

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
                                  {item.subTodos && (
                                    <button
                                      onClick={(e) => { e.stopPropagation(); toggleCollapsed(item.id); }}
                                      className={`shrink-0 transition-colors p-1.5 rounded-md hover:bg-muted ${!collapsed[item.id] ? "text-primary bg-primary/10" : "text-muted-foreground hover:text-foreground"}`}
                                      title="Show details"
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
                                {item.category && (
                                  <div className="mt-2.5">
                                    <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded-sm ${
                                      item.category === 'tech' ? 'bg-slate-200/60 text-slate-700' :
                                      item.category === 'content' ? 'bg-[#f0e8d5] text-[#7a5e3a]' :
                                      item.category === 'activity' ? 'bg-[#e0eceb] text-[#3f6567]' :
                                      'bg-[#fdeed9] text-[#c07a30]' // prep
                                    }`}>
                                      {item.category}
                                    </span>
                                  </div>
                                )}
                              </div>
                            </div>
                            {item.subTodos && !collapsed[item.id] && (
                              <div className="mt-2 p-3 bg-muted/30 rounded-md border border-border/50 space-y-2.5 animate-in fade-in slide-in-from-top-1 duration-200">
                                <h4 className="text-[10px] font-bold text-muted-foreground uppercase tracking-wider">Required Updates</h4>
                                {item.subTodos.map((task: any) => {
                                  const isSubDone = completedTasks.has(task.id);
                                  return (
                                    <div key={task.id} className={`flex items-start gap-2 text-xs font-body leading-relaxed group/sub cursor-pointer ${isSubDone ? 'opacity-60' : ''}`} onClick={(e) => { e.stopPropagation(); toggleSubTask(item.id, task.id); }}>
                                      <div className={`mt-0.5 w-3.5 h-3.5 rounded-[3px] border ${isSubDone ? 'bg-emerald-500/20 border-emerald-500/40 text-emerald-600' : 'border-muted-foreground/40 group-hover/sub:border-primary/50'} flex-shrink-0 flex items-center justify-center transition-colors`}>
                                        {isSubDone && <Check className="h-2.5 w-2.5" />}
                                      </div>
                                      <span className={`${isSubDone ? 'line-through decoration-muted-foreground/40 text-muted-foreground' : 'text-foreground/90 select-none'}`}>{task.label}</span>
                                    </div>
                                  );
                                })}
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
                            {item.subTodos && !collapsed[item.id] && (
                              <div className="mt-2 p-3 bg-muted/20 rounded-md border border-border/30 space-y-2.5 animate-in fade-in slide-in-from-top-1 duration-200 opacity-80">
                                <h4 className="text-[10px] font-bold text-muted-foreground uppercase tracking-wider">Required Updates</h4>
                                {item.subTodos.map((task: any) => {
                                  const isSubDone = completedTasks.has(task.id);
                                  return (
                                    <div key={task.id} className="flex items-start gap-2 text-xs font-body leading-relaxed group/sub cursor-pointer" onClick={(e) => { e.stopPropagation(); toggleSubTask(item.id, task.id); }}>
                                      <div className={`mt-0.5 w-3.5 h-3.5 rounded-[3px] border ${isSubDone ? 'bg-emerald-500/20 border-emerald-500/40 text-emerald-600' : 'border-muted-foreground/40 group-hover/sub:border-primary/50'} flex-shrink-0 flex items-center justify-center transition-colors`}>
                                        {isSubDone && <Check className="h-2.5 w-2.5" />}
                                      </div>
                                      <span className={`${isSubDone ? 'line-through decoration-muted-foreground/40 text-muted-foreground' : 'text-foreground/90 select-none'}`}>{task.label}</span>
                                    </div>
                                  );
                                })}
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
