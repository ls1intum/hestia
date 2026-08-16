import React, { useState, useEffect } from "react";
import { Presentation, Upload, Sparkles, Download, RefreshCw, X, File as FileIcon, ChevronLeft, ChevronRight, Check, ChevronUp, ChevronDown, Code, Save, AlertCircle } from "lucide-react";
import { toast } from "@/hooks/use-toast";
import { WorkshopSession, WorkshopInput, LearningGoalPlan, SlideData } from "@/lib/workshop-generator";

interface Props {
  session: WorkshopSession;
  meta: WorkshopInput;
  goals: LearningGoalPlan[];
  slidesCache: Record<number, SlideData[]>;
  setSlidesCache: React.Dispatch<React.SetStateAction<Record<number, SlideData[]>>>;
  onPreview: () => void;
  isOpen: boolean;
  setIsOpen: (open: boolean) => void;
}

export function SlideWorkstation({ session, meta, goals, slidesCache, setSlidesCache, onPreview, isOpen, setIsOpen }: Props) {
  const [template, setTemplate] = useState<File | null>(null);
  const [isGenerating, setIsGenerating] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [isUploading, setIsUploading] = useState(false);

  const [currentSlideIndex, setCurrentSlideIndex] = useState(0);

  const [isEditMode, setIsEditMode] = useState(false);
  const [editableCache, setEditableCache] = useState<Record<number, SlideData[]>>({});
  const [isSavingEdits, setIsSavingEdits] = useState(false);

  const hasSlides = Object.keys(slidesCache).length > 0;
  const allCachedSlides = (() => {
    if (!hasSlides) return [];
    const seen = new Set<string>();
    const result: SlideData[] = [];
    for (let i = 0; i < session.blocks.length; i++) {
      const slides = slidesCache[i] || [];
      for (const slide of slides) {
        if (slide.fixedInstructionFor) {
          if (seen.has(slide.fixedInstructionFor)) continue;
          seen.add(slide.fixedInstructionFor);
        }
        result.push(slide);
      }
    }
    return result;
  })();
  const totalSlides = hasSlides ? 1 + allCachedSlides.length : 0;

  const handleTemplateUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setTemplate(file);
    setIsUploading(true);
    const formData = new FormData();
    formData.append("template", file);
    try {
      const res = await fetch(import.meta.env.BASE_URL + `api/workshop/sessions/${session.id}/template`, {
        method: "POST",
        body: formData,
      });
      if (!res.ok) throw new Error("Template upload failed");
      toast({ title: "Template Saved", description: "Your branding template has been uploaded." });
    } catch (err) {
      toast({ title: "Upload Failed", description: err instanceof Error ? err.message : "Error", variant: "destructive" });
    } finally {
      setIsUploading(false);
    }
  };

  const handleGenerate = async () => {
    setIsGenerating(true);
    toast({ title: "Generating Lecture Slides", description: "This may take a minute…" });
    const newCache: Record<number, any[]> = {};
    try {
      const concurrencyLimit = 3;
      for (let i = 0; i < session.blocks.length; i += concurrencyLimit) {
        const chunk = session.blocks.slice(i, i + concurrencyLimit);
        const promises = chunk.map(async (block, chunkIdx) => {
          const actualIdx = i + chunkIdx;
          // Skip BUFFER and BREAK blocks — no slides generated for these
          if (block.phase === "BUFFER" || block.phase === "BREAK") {
            return { idx: actualIdx, slides: [] };
          }
          const res = await fetch(import.meta.env.BASE_URL + "api/workshop/export/block-slides", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ block, meta, goals }),
          });
          if (!res.ok) throw new Error("Failed to generate slides for block " + block.phaseLabel);
          return { idx: actualIdx, slides: await res.json() };
        });

        const results = await Promise.all(promises);
        for (const { idx, slides } of results) {
          newCache[idx] = slides;
        }
      }
      setSlidesCache(newCache);

      await fetch(import.meta.env.BASE_URL + `api/workshop/sessions/${session.id}/slides`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(newCache),
      });

      toast({ title: "Slides Generated!", description: "Scroll right to preview your deck." });
    } catch (e) {
      toast({ title: "Generation failed", description: e instanceof Error ? e.message : "Unknown error", variant: "destructive" });
    } finally {
      setIsGenerating(false);
    }
  };

  const handleDownload = async () => {
    setIsSaving(true);
    toast({ title: "Preparing Download…" });
    try {
      const res = await fetch(import.meta.env.BASE_URL + `api/workshop/sessions/${session.id}/export/pptx`);
      if (!res.ok) throw new Error("Export failed");

      const blob = await res.blob();
      const safeName = (session.title || "workshop").slice(0, 40).replace(/[^a-zA-Z0-9.-]+/gi, "_").toLowerCase();

      const url = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `${safeName}_slides.pptx`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(url);

      toast({ title: "Downloaded!", description: "Your slide deck is ready." });
    } catch (e) {
      // Fallback
      try {
        const formData = new FormData();
        formData.append("session", JSON.stringify(session));
        formData.append("meta", JSON.stringify(meta));
        formData.append("slides", JSON.stringify(allCachedSlides));
        if (template) formData.append("template", template);

        const res2 = await fetch(import.meta.env.BASE_URL + "api/workshop/export/pptx-with-template", {
          method: "POST",
          body: formData,
        });
        if (!res2.ok) throw new Error("Assembly failed");
        const blob = await res2.blob();
        const safeName = (session.title || "workshop").slice(0, 40).replace(/[^a-zA-Z0-9.-]+/gi, "_").toLowerCase();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = `${safeName}_slides.pptx`;
        document.body.appendChild(a);
        a.click();
        a.remove();
        window.URL.revokeObjectURL(url);
        toast({ title: "Downloaded!" });
      } catch (err) {
        toast({ title: "Download Failed", description: err instanceof Error ? err.message : "Unknown error", variant: "destructive" });
      }
    } finally {
      setIsSaving(false);
    }
  };

  const handleEditSlide = () => {
    setEditableCache(JSON.parse(JSON.stringify(slidesCache)));
    setIsEditMode(true);
  };

  const handleSaveEdits = async () => {
    setIsSavingEdits(true);
    try {
      setSlidesCache(editableCache);
      const res = await fetch(import.meta.env.BASE_URL + `api/workshop/sessions/${session.id}/slides`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(editableCache),
      });
      if (!res.ok) throw new Error("Failed to save slides to server");
      toast({ title: "Slides Updated", description: "Your manual edits have been saved." });
      setIsEditMode(false);
    } catch (e) {
      toast({ title: "Save Failed", description: e instanceof Error ? e.message : "Error saving", variant: "destructive" });
    } finally {
      setIsSavingEdits(false);
    }
  };

  const nextSlide = () => setCurrentSlideIndex(i => Math.min(totalSlides - 1, i + 1));
  const prevSlide = () => setCurrentSlideIndex(i => Math.max(0, i - 1));

  let currentBlockIndex = -1;
  let currentSlideWithinBlock = -1;
  if (isEditMode && currentSlideIndex > 0) {
    let flatIndex = 0;
    for (let i = 0; i < session.blocks.length; i++) {
      const blockSlides = editableCache[i] || [];
      const contentSlideIndex = currentSlideIndex - 1;
      if (contentSlideIndex >= flatIndex && contentSlideIndex < flatIndex + blockSlides.length) {
        currentBlockIndex = i;
        currentSlideWithinBlock = contentSlideIndex - flatIndex;
        break;
      }
      flatIndex += blockSlides.length;
    }
  }

  // --- HTML Rendering Engine ---

  /**
   * Coerce any value the backend might return in a string field to an actual string.
   * The LLM occasionally returns notes/bullets as objects or arrays instead of strings.
   */
  const coerceToString = (value: unknown): string => {
    if (value === undefined || value === null) return "";
    if (typeof value === "string") return value;
    if (Array.isArray(value)) {
      return value.map((v) => coerceToString(v)).filter(Boolean).join("\n");
    }
    if (typeof value === "object") {
      const obj = value as Record<string, unknown>;
      // Priority 1: Common LLM single-string keys
      if (typeof obj.text === "string") return obj.text;
      if (typeof obj.content === "string") return obj.content;
      if (typeof obj.value === "string") return obj.value;
      if (typeof obj.notes === "string") return obj.notes;
      if (typeof obj.summary === "string") return obj.summary;

      // Priority 2: Format as readable "Label: value" lines (e.g. for already-cached structured notes)
      const lines: string[] = [];
      for (const [key, val] of Object.entries(obj)) {
        const strVal = coerceToString(val);
        if (!strVal) continue;
        
        // Convert camelCase/snake_case to Title Case (e.g., answerKey -> Answer Key)
        let label = key.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/[_-]+/g, ' ').trim();
        label = label.charAt(0).toUpperCase() + label.slice(1);
        
        lines.push(`${label}: ${strVal}`);
      }
      if (lines.length > 0) return lines.join("\n");
      
      return "";
    }
    return String(value);
  };

  const renderBoldPlaceholders = (text: unknown) => {
    const str = coerceToString(text);
    if (!str) return null;
    const parts = str.split(/(\[.*?\])/g);
    return parts.map((part, index) => {
      if (part.startsWith('[') && part.endsWith(']')) {
        return <strong key={index} style={{ fontWeight: 800 }}>{part}</strong>;
      }
      return part;
    });
  };

  const renderSlideHtml = (index: number) => {
    if (index === 0) {
      return (
        <div style={{ background: '#ffffff' }} className="w-full h-full flex flex-col justify-center px-12 text-left">
          <div style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', color: 'var(--hestia-primary)', textTransform: 'uppercase', marginBottom: '12px', letterSpacing: '0.05em' }}>
            {meta?.sessionType || "Lecture Slides"}
          </div>
          <h1 style={{ fontFamily: 'var(--hestia-font-body)', fontSize: '2.5rem', fontWeight: 'bold', color: 'var(--hestia-text)', lineHeight: 1.2, marginBottom: '24px' }}>
            {session.title || "Workshop Session"}
          </h1>
          <div style={{ height: '4px', width: '64px', background: 'var(--hestia-primary)' }} />
        </div>
      );
    }

    const slideData = allCachedSlides[index - 1];
    if (!slideData) return null;

    let block = undefined;
    let flatIndex = 0;
    for (let i = 0; i < session.blocks.length; i++) {
      const blockSlides = slidesCache[i] || [];
      if (index - 1 >= flatIndex && index - 1 < flatIndex + blockSlides.length) {
         block = session.blocks[i];
         break;
      }
      flatIndex += blockSlides.length;
    }

    const phaseName = block?.phase?.toLowerCase() || 'setup';
    let borderColor = 'var(--hestia-phase-setup)';
    if (phaseName.includes('lecture') || phaseName.includes('arrive') || phaseName.includes('intro')) borderColor = 'var(--hestia-phase-lecture)';
    if (phaseName.includes('practice') || phaseName.includes('cycle') || phaseName.includes('activate')) borderColor = 'var(--hestia-phase-practice)';
    if (phaseName.includes('evaluate') || phaseName.includes('check')) borderColor = 'var(--hestia-phase-evaluate)';
    if (phaseName.includes('summary') || phaseName.includes('wrap')) borderColor = 'var(--hestia-phase-summary)';

    return (
      <div style={{ background: '#ffffff', borderLeft: `4px solid ${borderColor}` }} className="w-full h-full flex flex-col pt-4 pb-4 px-6 text-left relative overflow-hidden rounded-r-md">
        <div className="mb-3 shrink-0">
          {slideData.subtitle && (
            <div style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.45rem', color: 'var(--hestia-text-muted)', textTransform: 'uppercase', marginBottom: '4px', letterSpacing: '0.05em' }}>
              {slideData.subtitle}
            </div>
          )}
          <h2 style={{ fontFamily: 'var(--hestia-font-body)', fontSize: '1rem', fontWeight: 'bold', color: 'var(--hestia-text)', lineHeight: 1.2 }}>
            {slideData.title || "Slide"}
          </h2>
        </div>

        <div className="flex-1 flex flex-col gap-1 overflow-hidden">
          {slideData.layout === 'activity_tiled' ? (
            <div className="grid grid-cols-2 gap-2 h-full">
              <div className="col-span-2 border rounded p-2 flex flex-col justify-center" style={{ background: 'color-mix(in srgb, var(--hestia-text) 3%, var(--hestia-surface))', borderLeft: `2px solid ${borderColor}`, borderColor: 'var(--hestia-border)' }}>
                <h3 className="text-xs font-bold mb-1 flex items-center gap-1" style={{ color: borderColor }}>
                  <span>📝</span> The Prompt
                </h3>
                <p className="text-xs leading-snug text-gray-900">{slideData.activityPrompt || "Prompt placeholder"}</p>
              </div>
              <div className="border rounded p-2 flex flex-col justify-center" style={{ background: 'color-mix(in srgb, var(--hestia-text) 3%, var(--hestia-surface))', borderColor: 'var(--hestia-border)' }}>
                 <h3 className="text-xs font-bold mb-1 flex items-center gap-1" style={{ color: borderColor }}>
                   <span>⏱️</span> Instructions
                 </h3>
                 <ul className="text-[10px] text-gray-900 leading-snug list-disc pl-3 m-0 space-y-0.5">
                   {slideData.activityInstructions?.map((inst, i) => <li key={i}>{renderBoldPlaceholders(inst)}</li>)}
                 </ul>
              </div>
              <div className="border rounded p-2 flex flex-col justify-center" style={{ background: 'color-mix(in srgb, var(--hestia-text) 3%, var(--hestia-surface))', borderColor: 'var(--hestia-border)' }}>
                 <h3 className="text-xs font-bold mb-1 flex items-center gap-1" style={{ color: borderColor }}>
                   <span>🎯</span> Output Expectation
                 </h3>
                 <p className="text-[10px] leading-snug text-gray-900 italic">{slideData.activityOutputExpectation || "Expectation placeholder"}</p>
              </div>
            </div>
          ) : slideData.layout === 'activity_sidebar' ? (
            <div className="flex gap-2 h-full">
              <div className="flex-[2] border rounded p-3 flex flex-col justify-center" style={{ background: 'color-mix(in srgb, var(--hestia-text) 3%, var(--hestia-surface))', borderLeft: `2px solid ${borderColor}`, borderColor: 'var(--hestia-border)' }}>
                <h3 className="text-xs font-bold mb-2 flex items-center gap-1" style={{ color: borderColor }}>
                  <span>🌍</span> The Scenario / Task
                </h3>
                <p className="text-xs leading-relaxed text-gray-900">{slideData.activityPrompt || "Prompt placeholder"}</p>
              </div>
              <div className="flex-[1] border rounded p-3 flex flex-col justify-center" style={{ background: 'color-mix(in srgb, var(--hestia-text) 3%, var(--hestia-surface))', borderColor: 'var(--hestia-border)' }}>
                <h3 className="text-xs font-bold mb-2 flex items-center gap-1" style={{ color: borderColor }}>
                  <span>📋</span> Details
                </h3>
                <ul className="text-[10px] text-gray-900 leading-snug list-disc pl-3 m-0 space-y-1.5">
                  {slideData.activityInstructions?.map((inst, i) => <li key={i}>{renderBoldPlaceholders(inst)}</li>)}
                </ul>
              </div>
            </div>
          ) : slideData.layout === 'activity_grid3' ? (
            <div className="flex flex-col gap-2 h-full">
              <div className="flex-1 border rounded p-4 flex flex-col justify-center" style={{ background: 'color-mix(in srgb, var(--hestia-text) 3%, var(--hestia-surface))', borderLeft: `2px solid ${borderColor}`, borderColor: 'var(--hestia-border)' }}>
                <p className="text-sm font-semibold leading-relaxed text-gray-900">{slideData.activityPrompt || "Prompt placeholder"}</p>
              </div>
              <div className="grid grid-cols-3 gap-2">
                {slideData.activityInstructions?.slice(0, 3).map((inst, i) => {
                  const parts = inst.split(':');
                  const title = parts[0];
                  const body = parts.slice(1).join(':').trim();
                  return (
                    <div key={i} className="border rounded p-2 flex flex-col justify-center" style={{ background: 'color-mix(in srgb, var(--hestia-text) 3%, var(--hestia-surface))', borderColor: 'var(--hestia-border)' }}>
                      <h3 className="text-[10px] font-bold mb-1" style={{ color: borderColor }}>
                        {i + 1}. {title}
                      </h3>
                      <p className="text-[9px] leading-snug text-gray-900 m-0">{body}</p>
                    </div>
                  );
                })}
              </div>
            </div>
          ) : slideData.layout === 'live_poll' ? (
            <div className="flex items-center justify-between h-full">
              <div className="flex-1 pr-4">
                <p className="text-[0.9rem] font-bold text-gray-900 mb-2 leading-snug">{slideData.pollQuestion || "Poll Question Placeholder"}</p>
                <div className="flex flex-col gap-1.5">
                  {slideData.pollOptions?.map((opt, i) => (
                    <div key={i} className="border rounded px-2 py-1 font-medium text-[10px]" style={{ background: 'color-mix(in srgb, var(--hestia-text) 3%, var(--hestia-surface))', borderColor: 'var(--hestia-border)' }}>
                      {opt}
                    </div>
                  ))}
                </div>
              </div>
              <div className="flex flex-col items-center justify-center pl-4 border-l border-dashed" style={{ borderColor: 'var(--hestia-border)' }}>
                 <div className="w-[60px] h-[60px] border-2 border-dashed rounded flex items-center justify-center font-semibold mb-2" style={{ background: 'color-mix(in srgb, var(--hestia-text) 5%, var(--hestia-surface))', borderColor: 'var(--hestia-border)', color: 'var(--hestia-text-muted)', fontSize: '8px' }}>
                     [ QR ]
                 </div>
                 <div className="text-center font-bold text-[10px]">
                   <p className="text-[8px]">menti.com</p>
                   <p className="text-xs font-mono tracking-widest mt-0.5">1234</p>
                 </div>
              </div>
            </div>
          ) : slideData.layout === 'concept_map' ? (
            <div className="flex-1 flex flex-col items-center justify-center text-center p-2">
               <h3 className="text-[0.9rem] mb-2 font-bold" style={{ color: 'var(--hestia-primary)' }}>One-Minute Paper</h3>
               <div className="text-[10px] max-w-lg text-left space-y-2">
                 {slideData.bullets?.map((bullet, i) => (
                   <p key={i} className="leading-snug">
                     {renderBoldPlaceholders(typeof bullet === 'string' ? bullet : String(bullet))}
                   </p>
                 ))}
               </div>
            </div>
          ) : slideData.layout === 'lecture_placeholder' ? (
            // Lecture placeholder — instructor inserts own content here
            <div className="flex-1 flex flex-col items-center justify-center text-center p-4">
              <div className="border-2 border-dashed rounded-lg p-6 w-full max-w-lg" style={{ borderColor: 'var(--hestia-phase-lecture)', background: 'color-mix(in srgb, var(--hestia-phase-lecture) 5%, white)' }}>
                <div className="text-2xl mb-3">📖</div>
                <p className="text-xs font-semibold mb-1" style={{ color: 'var(--hestia-phase-lecture)' }}>INSTRUCTOR LECTURE CONTENT</p>
                <p className="text-[10px] leading-snug" style={{ color: 'var(--hestia-text-muted)' }}>
                  {slideData.bullets?.[0] ?? "Insert your lecture slides here"}
                </p>
              </div>
            </div>
          ) : slideData.layout === 'debrief' ? (
            // Per-cycle debrief — single centred reflective question
            <div className="flex-1 flex flex-col items-center justify-center text-center p-4">
              <div className="text-xl mb-3">💬</div>
              <p className="text-sm italic leading-relaxed max-w-md font-medium" style={{ color: 'var(--hestia-text)' }}>
                {slideData.debriefQuestion
                  ? slideData.debriefQuestion
                  : slideData.bullets?.[0] ?? "Reflect on what you just learned."}
              </p>
            </div>
          ) : (
            slideData.bullets?.map((bullet, i) => (
              <div key={i} className="flex items-start gap-1.5 mb-1">
                <div style={{ width: '4px', height: '4px', borderRadius: '50%', background: borderColor, marginTop: '6px', flexShrink: 0 }} />
                <p style={{ fontFamily: 'var(--hestia-font-body)', fontSize: '0.8rem', fontWeight: '500', color: 'var(--hestia-text)', lineHeight: 1.3 }}>
                  {renderBoldPlaceholders(typeof bullet === 'string' ? bullet : String(bullet))}
                </p>
              </div>
            ))
          )}
        </div>
      </div>
    );
  };

  return (
    <div
      className={`mb-6 transition-all duration-300 overflow-hidden ${hasSlides ? "border-[1.5px] border-emerald-400" : ""}`}
      style={hasSlides ? {
        background: 'var(--hestia-surface)',
        borderRadius: 'var(--hestia-radius-xl)',
        boxShadow: 'var(--hestia-shadow-md)'
      } : {
        background: 'var(--hestia-surface)',
        border: '1px solid var(--hestia-border)',
        borderRadius: 'var(--hestia-radius-xl)'
      }}
    >
      {/* HEADER */}
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center gap-3 transition-colors text-left"
        style={{
          background: 'var(--hestia-surface)',
          borderBottom: '1px solid var(--hestia-border)',
          padding: '14px 20px',
        }}
      >
        <div
          className={`shrink-0 flex items-center justify-center ${hasSlides ? "bg-emerald-100 text-emerald-600" : ""}`}
          style={{
            width: '36px',
            height: '36px',
            borderRadius: 'var(--hestia-radius-lg)',
            background: hasSlides ? undefined : 'var(--hestia-bg)',
            color: hasSlides ? undefined : 'var(--hestia-text-muted)'
          }}
        >
          <Presentation className="h-5 w-5" />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <h3 style={{ fontFamily: 'var(--hestia-font-body)', fontWeight: 600, color: 'var(--hestia-text)' }}>
              Slide Workstation
            </h3>
            {hasSlides && (
              <span className="bg-emerald-100 text-emerald-700 border border-emerald-200" style={{
                fontFamily: 'var(--hestia-font-mono)',
                fontSize: '0.75rem',
                fontWeight: 600,
                borderRadius: 'var(--hestia-radius-full)',
                padding: '2px 10px'
              }}>
                ✓ SLIDES GENERATED
              </span>
            )}
          </div>
          <p style={{ fontFamily: 'var(--hestia-font-body)', fontSize: '0.75rem', color: 'var(--hestia-text-muted)', marginTop: '2px' }}>
            {hasSlides ? "Ready to preview and download" : "Generate presentation slides for this session"}
          </p>
        </div>
        {hasSlides && (
          <button
            onClick={(e) => {
              e.stopPropagation();
              if (isEditMode) setIsEditMode(false);
              else handleEditSlide();
              if (!isOpen && !isEditMode) setIsOpen(true);
            }}
            disabled={isGenerating || isUploading || isSavingEdits}
            className="flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-md transition-colors focus:outline-none focus-visible:ring-2 border border-emerald-200 text-emerald-700 bg-white hover:bg-emerald-50 mr-2"
            style={{ fontFamily: 'var(--hestia-font-body)' }}
          >
            <Code className="h-4 w-4" />
            <span style={{ fontSize: '0.75rem', fontWeight: 600 }}>
              {isEditMode ? "View Slides" : "Edit Slides"}
            </span>
          </button>
        )}
        <div
          className="flex items-center justify-center transition-colors"
          style={{
            color: 'var(--hestia-text-muted)',
            borderRadius: 'var(--hestia-radius-md)',
            padding: '4px'
          }}
          onMouseEnter={(e) => e.currentTarget.style.background = 'var(--hestia-primary-muted)'}
          onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}
        >
          {isOpen ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
        </div>
      </button>

      {/* BODY */}
      {isOpen && (
        <div className="flex flex-col lg:flex-row" style={{ borderTop: '1px solid var(--hestia-border)' }}>

          {/* LEFT SIDEBAR */}
          <div className="shrink-0 p-5 flex flex-col gap-6" style={{ width: '220px', borderRight: '1px solid var(--hestia-border)', background: 'var(--hestia-surface)' }}>

            {/* Template Upload */}
            <div className="space-y-3">
              <h4 style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--hestia-text-muted)' }}>
                PowerPoint Template
              </h4>
              <div className="relative group">
                <input
                  type="file"
                  accept=".pptx"
                  onChange={handleTemplateUpload}
                  className="absolute inset-0 w-full h-full opacity-0 cursor-pointer z-10"
                />
                <div
                  className="border-[2px] border-dashed rounded-md p-3 flex flex-col items-center justify-center gap-1 transition-all min-h-[72px]"
                  style={template ? {
                    borderColor: 'var(--hestia-accent)',
                    background: 'color-mix(in srgb, var(--hestia-accent) 5%, var(--hestia-surface))',
                    borderRadius: 'var(--hestia-radius-md)'
                  } : {
                    borderColor: 'var(--hestia-border)',
                    background: 'var(--hestia-surface)',
                    borderRadius: 'var(--hestia-radius-md)'
                  }}
                  onMouseEnter={(e) => { if (!template) { e.currentTarget.style.borderColor = 'var(--hestia-accent)'; e.currentTarget.style.background = 'color-mix(in srgb, var(--hestia-accent) 5%, var(--hestia-surface))' } }}
                  onMouseLeave={(e) => { if (!template) { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.background = 'var(--hestia-surface)' } }}
                >
                  {isUploading ? (
                    <RefreshCw className="h-5 w-5 animate-spin" style={{ color: 'var(--hestia-accent)' }} />
                  ) : template ? (
                    <>
                      <div className="h-8 w-8 rounded-md flex items-center justify-center mb-1" style={{ background: 'color-mix(in srgb, var(--hestia-accent) 12%, var(--hestia-surface))' }}>
                        <FileIcon className="h-4 w-4" style={{ color: 'var(--hestia-accent)' }} />
                      </div>
                      <span className="text-[11px] font-medium text-center truncate w-full px-1" style={{ color: 'var(--hestia-accent)', fontFamily: 'var(--hestia-font-body)' }}>{template.name}</span>
                      <span className="text-[9px]" style={{ color: 'color-mix(in srgb, var(--hestia-accent) 70%, transparent)', fontFamily: 'var(--hestia-font-body)' }}>Click to replace</span>
                    </>
                  ) : (
                    <>
                      <Upload className="h-5 w-5 mb-1 transition-colors group-hover:text-[var(--hestia-primary)]" style={{ color: 'var(--hestia-text-muted)' }} />
                      <span className="text-[0.875rem] font-medium text-center" style={{ color: 'var(--hestia-text)', fontFamily: 'var(--hestia-font-body)' }}>Upload</span>
                      <span className="text-[0.75rem] text-center" style={{ color: 'var(--hestia-text-muted)', fontFamily: 'var(--hestia-font-body)' }}>Hint: optional branding</span>
                    </>
                  )}
                </div>
              </div>
            </div>

            <div style={{ height: '1px', background: 'var(--hestia-border)' }} />

            {/* Actions */}
            <div className="space-y-3">
              <h4 style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--hestia-text-muted)' }}>
                Actions
              </h4>

              {hasSlides && (
                <div className="flex items-center justify-center gap-1.5 px-2 py-1.5 rounded-md bg-emerald-50 border border-emerald-200">
                  <Check className="h-3 w-3 text-emerald-600" />
                  <span className="text-emerald-700" style={{ fontSize: '0.75rem', fontFamily: 'var(--hestia-font-mono)', fontWeight: 500 }}>
                    {allCachedSlides.length} slides generated
                  </span>
                </div>
              )}

              <button
                onClick={handleGenerate}
                disabled={isGenerating || isUploading}
                className="w-full flex items-center justify-center gap-2 transition-colors focus:outline-none focus-visible:ring-2 disabled:opacity-50"
                style={{
                  background: 'var(--hestia-primary)',
                  color: 'white',
                  borderRadius: 'var(--hestia-radius-md)',
                  padding: '8px',
                  fontFamily: 'var(--hestia-font-body)',
                  fontSize: '0.875rem',
                  fontWeight: 500
                }}
                onMouseEnter={(e) => e.currentTarget.style.background = 'var(--hestia-primary-hover)'}
                onMouseLeave={(e) => e.currentTarget.style.background = 'var(--hestia-primary)'}
              >
                {isGenerating
                  ? <RefreshCw className="h-4 w-4 animate-spin" />
                  : <Sparkles className="h-4 w-4" />
                }
                {isGenerating ? "Generating…" : hasSlides ? "Regenerate Slides" : "Generate Slides"}
              </button>

              {hasSlides && (
                <button
                  onClick={handleDownload}
                  disabled={isSaving}
                  className="w-full flex items-center justify-center gap-2 transition-colors focus:outline-none focus-visible:ring-2 disabled:opacity-50"
                  style={{
                    background: 'transparent',
                    border: '1px solid var(--hestia-border)',
                    color: 'var(--hestia-text)',
                    borderRadius: 'var(--hestia-radius-md)',
                    padding: '8px',
                    fontFamily: 'var(--hestia-font-body)',
                    fontSize: '0.875rem',
                    fontWeight: 500
                  }}
                  onMouseEnter={(e) => e.currentTarget.style.background = 'var(--hestia-primary-muted)'}
                  onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}
                >
                  {isSaving
                    ? <RefreshCw className="h-4 w-4 animate-spin" />
                    : <Download className="h-4 w-4" />
                  }
                  {isSaving ? "Exporting…" : "Download PPTX"}
                </button>
              )}
            </div>
          </div>

          {/* RIGHT: Preview */}
          <div className="flex-1 flex flex-col min-h-[600px]" style={{ background: 'var(--hestia-surface)' }}>
            {!hasSlides ? (
              <div className="flex-1 flex flex-col items-center justify-center gap-4 p-8 text-center" style={{ background: 'var(--hestia-surface)' }}>
                <div className="h-16 w-16 rounded-xl flex items-center justify-center" style={{ background: 'var(--hestia-bg)' }}>
                  <Presentation className="h-8 w-8" style={{ color: 'var(--hestia-text-muted)' }} />
                </div>
                <div>
                  <p style={{ fontFamily: 'var(--hestia-font-display)', fontSize: '1.25rem', fontWeight: 600, color: 'var(--hestia-text)' }}>No slides yet</p>
                  <p style={{ fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text-muted)', marginTop: '4px' }}>
                    Click <strong>Generate Slides</strong> to build your presentation.
                  </p>
                </div>
              </div>
            ) : isEditMode ? (
              <div className="flex flex-col flex-1" style={{ background: 'var(--hestia-surface)' }}>
                {/* Editor Header */}
                <div className="flex items-center justify-between px-4 h-12 shrink-0" style={{ borderBottom: '1px solid var(--hestia-border)' }}>
                  <span style={{ fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', fontWeight: 600, color: 'var(--hestia-text)' }}>Slide Editor</span>
                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => setIsEditMode(false)}
                      disabled={isSavingEdits}
                      className="px-3 py-1.5 flex items-center gap-1.5 rounded-md transition-colors focus:outline-none focus-visible:ring-2 disabled:opacity-50"
                      style={{ fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                      onMouseEnter={(e) => e.currentTarget.style.background = 'var(--hestia-primary-muted)'}
                      onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}
                    >
                      <X className="h-4 w-4" /> Cancel
                    </button>
                    <button
                      onClick={handleSaveEdits}
                      disabled={isSavingEdits}
                      className="px-3 py-1.5 flex items-center gap-1.5 rounded-md transition-colors text-white focus:outline-none focus-visible:ring-2 disabled:opacity-50"
                      style={{ background: 'var(--hestia-primary)', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', fontWeight: 500 }}
                      onMouseEnter={(e) => e.currentTarget.style.background = 'var(--hestia-primary-hover)'}
                      onMouseLeave={(e) => e.currentTarget.style.background = 'var(--hestia-primary)'}
                    >
                      {isSavingEdits ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
                      Save & Update
                    </button>
                  </div>
                </div>

                {/* Editor Form */}
                {currentSlideIndex === 0 ? (
                  <div className="flex-1 p-6 flex flex-col items-center justify-center text-center">
                    <div style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', color: 'var(--hestia-primary)', textTransform: 'uppercase', marginBottom: '8px' }}>
                      {meta?.sessionType || "Lecture Slides"}
                    </div>
                    <h3 style={{ fontFamily: 'var(--hestia-font-display)', fontSize: '2rem', fontWeight: 'bold', color: 'var(--hestia-text)' }}>
                      {session.title || "Workshop Session"}
                    </h3>
                    <p style={{ fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text-muted)', marginTop: '24px', maxWidth: '300px' }}>
                      The Title slide is automatically generated from your session settings. It cannot be edited here.
                    </p>
                  </div>
                ) : currentBlockIndex !== -1 ? (
                  <div className="flex-1 p-8 overflow-y-auto space-y-6">
                    {(() => {
                      const slide = editableCache[currentBlockIndex][currentSlideWithinBlock];
                      const layout = slide?.layout || 'default';

                      return (
                        <>
                          <div className="space-y-2">
                            <label style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-text-muted)', textTransform: 'uppercase' }}>TITLE</label>
                            <input
                              className="w-full focus:outline-none transition-all"
                              style={{ background: 'var(--hestia-surface)', border: '1.5px solid var(--hestia-border)', borderRadius: 'var(--hestia-radius-sm)', padding: '10px 12px', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                              onFocus={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-primary)'; e.currentTarget.style.boxShadow = '0 0 0 3px var(--hestia-primary-muted)' }}
                              onBlur={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.boxShadow = 'none' }}
                              value={slide?.title || ""}
                              onChange={e => {
                                const newCache = { ...editableCache };
                                newCache[currentBlockIndex][currentSlideWithinBlock].title = e.target.value;
                                setEditableCache(newCache);
                              }}
                            />
                          </div>
                          <div className="space-y-2">
                            <label style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-text-muted)', textTransform: 'uppercase' }}>SUBTITLE</label>
                            <input
                              className="w-full focus:outline-none transition-all"
                              style={{ background: 'var(--hestia-surface)', border: '1.5px solid var(--hestia-border)', borderRadius: 'var(--hestia-radius-sm)', padding: '10px 12px', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                              onFocus={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-primary)'; e.currentTarget.style.boxShadow = '0 0 0 3px var(--hestia-primary-muted)' }}
                              onBlur={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.boxShadow = 'none' }}
                              value={slide?.subtitle || ""}
                              onChange={e => {
                                const newCache = { ...editableCache };
                                newCache[currentBlockIndex][currentSlideWithinBlock].subtitle = e.target.value;
                                setEditableCache(newCache);
                              }}
                            />
                          </div>

                          {layout.startsWith('activity_') && (
                            <>
                              <div className="space-y-2">
                                <label style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-text-muted)', textTransform: 'uppercase' }}>ACTIVITY PROMPT</label>
                                <textarea
                                  className="w-full h-24 focus:outline-none transition-all resize-y"
                                  style={{ background: 'var(--hestia-surface)', border: '1.5px solid var(--hestia-border)', borderRadius: 'var(--hestia-radius-sm)', padding: '10px 12px', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                                  onFocus={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-primary)'; e.currentTarget.style.boxShadow = '0 0 0 3px var(--hestia-primary-muted)' }}
                                  onBlur={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.boxShadow = 'none' }}
                                  value={slide?.activityPrompt || ""}
                                  onChange={e => {
                                    const newCache = { ...editableCache };
                                    newCache[currentBlockIndex][currentSlideWithinBlock].activityPrompt = e.target.value;
                                    setEditableCache(newCache);
                                  }}
                                />
                              </div>
                              <div className="space-y-2">
                                <label style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-text-muted)', textTransform: 'uppercase' }}>INSTRUCTIONS (ONE PER LINE)</label>
                                <textarea
                                  className="w-full h-24 focus:outline-none transition-all resize-y"
                                  style={{ background: 'var(--hestia-surface)', border: '1.5px solid var(--hestia-border)', borderRadius: 'var(--hestia-radius-sm)', padding: '10px 12px', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                                  onFocus={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-primary)'; e.currentTarget.style.boxShadow = '0 0 0 3px var(--hestia-primary-muted)' }}
                                  onBlur={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.boxShadow = 'none' }}
                                  value={(slide?.activityInstructions || []).join("\n")}
                                  onChange={e => {
                                    const newCache = { ...editableCache };
                                    newCache[currentBlockIndex][currentSlideWithinBlock].activityInstructions = e.target.value.split("\n");
                                    setEditableCache(newCache);
                                  }}
                                />
                              </div>
                              <div className="space-y-2">
                                <label style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-text-muted)', textTransform: 'uppercase' }}>OUTPUT EXPECTATION</label>
                                <input
                                  className="w-full focus:outline-none transition-all"
                                  style={{ background: 'var(--hestia-surface)', border: '1.5px solid var(--hestia-border)', borderRadius: 'var(--hestia-radius-sm)', padding: '10px 12px', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                                  onFocus={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-primary)'; e.currentTarget.style.boxShadow = '0 0 0 3px var(--hestia-primary-muted)' }}
                                  onBlur={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.boxShadow = 'none' }}
                                  value={slide?.activityOutputExpectation || ""}
                                  onChange={e => {
                                    const newCache = { ...editableCache };
                                    newCache[currentBlockIndex][currentSlideWithinBlock].activityOutputExpectation = e.target.value;
                                    setEditableCache(newCache);
                                  }}
                                />
                              </div>
                            </>
                          )}

                          {layout === 'live_poll' && (
                            <>
                              <div className="space-y-2">
                                <label style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-text-muted)', textTransform: 'uppercase' }}>POLL QUESTION</label>
                                <textarea
                                  className="w-full h-20 focus:outline-none transition-all resize-y"
                                  style={{ background: 'var(--hestia-surface)', border: '1.5px solid var(--hestia-border)', borderRadius: 'var(--hestia-radius-sm)', padding: '10px 12px', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                                  onFocus={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-primary)'; e.currentTarget.style.boxShadow = '0 0 0 3px var(--hestia-primary-muted)' }}
                                  onBlur={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.boxShadow = 'none' }}
                                  value={slide?.pollQuestion || ""}
                                  onChange={e => {
                                    const newCache = { ...editableCache };
                                    newCache[currentBlockIndex][currentSlideWithinBlock].pollQuestion = e.target.value;
                                    setEditableCache(newCache);
                                  }}
                                />
                              </div>
                              <div className="space-y-2">
                                <label style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-text-muted)', textTransform: 'uppercase' }}>OPTIONS (ONE PER LINE)</label>
                                <textarea
                                  className="w-full h-24 focus:outline-none transition-all resize-y"
                                  style={{ background: 'var(--hestia-surface)', border: '1.5px solid var(--hestia-border)', borderRadius: 'var(--hestia-radius-sm)', padding: '10px 12px', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                                  onFocus={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-primary)'; e.currentTarget.style.boxShadow = '0 0 0 3px var(--hestia-primary-muted)' }}
                                  onBlur={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.boxShadow = 'none' }}
                                  value={(slide?.pollOptions || []).join("\n")}
                                  onChange={e => {
                                    const newCache = { ...editableCache };
                                    newCache[currentBlockIndex][currentSlideWithinBlock].pollOptions = e.target.value.split("\n");
                                    setEditableCache(newCache);
                                  }}
                                />
                              </div>
                            </>
                          )}

                          {layout === 'debrief' && (
                            <div className="space-y-2">
                              <label style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-text-muted)', textTransform: 'uppercase' }}>REFLECTION QUESTION</label>
                              <textarea
                                className="w-full h-24 focus:outline-none transition-all resize-y"
                                style={{ background: 'var(--hestia-surface)', border: '1.5px solid var(--hestia-border)', borderRadius: 'var(--hestia-radius-sm)', padding: '10px 12px', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                                onFocus={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-primary)'; e.currentTarget.style.boxShadow = '0 0 0 3px var(--hestia-primary-muted)' }}
                                onBlur={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.boxShadow = 'none' }}
                                value={slide?.debriefQuestion || ""}
                                onChange={e => {
                                  const newCache = { ...editableCache };
                                  newCache[currentBlockIndex][currentSlideWithinBlock].debriefQuestion = e.target.value;
                                  setEditableCache(newCache);
                                }}
                              />
                            </div>
                          )}

                          {(!layout.startsWith('activity_') && layout !== 'live_poll' && layout !== 'debrief') && (
                            <div className="space-y-2">
                              <label style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-text-muted)', textTransform: 'uppercase' }}>CONTENT (BULLETS)</label>
                              <textarea
                                className="w-full h-32 focus:outline-none transition-all resize-y"
                                style={{ background: 'var(--hestia-surface)', border: '1.5px solid var(--hestia-border)', borderRadius: 'var(--hestia-radius-sm)', padding: '10px 12px', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                                onFocus={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-primary)'; e.currentTarget.style.boxShadow = '0 0 0 3px var(--hestia-primary-muted)' }}
                                onBlur={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.boxShadow = 'none' }}
                                value={(slide?.bullets || []).join("\n")}
                                onChange={e => {
                                  const newCache = { ...editableCache };
                                  newCache[currentBlockIndex][currentSlideWithinBlock].bullets = e.target.value.split("\n");
                                  setEditableCache(newCache);
                                }}
                              />
                            </div>
                          )}

                          <div className="space-y-2 mt-4 pt-4" style={{ borderTop: '1px dashed var(--hestia-border)' }}>
                            <label style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-primary)', textTransform: 'uppercase' }}>SPEAKER NOTES</label>
                            <textarea
                              className="w-full h-24 focus:outline-none transition-all resize-y"
                              style={{ background: 'var(--hestia-surface)', border: '1.5px solid var(--hestia-border)', borderRadius: 'var(--hestia-radius-sm)', padding: '10px 12px', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                              onFocus={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-primary)'; e.currentTarget.style.boxShadow = '0 0 0 3px var(--hestia-primary-muted)' }}
                              onBlur={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.boxShadow = 'none' }}
                              value={coerceToString(slide?.notes) || ""}
                              onChange={e => {
                                const newCache = { ...editableCache };
                                newCache[currentBlockIndex][currentSlideWithinBlock].notes = e.target.value;
                                setEditableCache(newCache);
                              }}
                            />
                          </div>
                        </>
                      );
                    })()}
                  </div>
                ) : (
                  <div className="flex-1 flex items-center justify-center" style={{ color: 'var(--hestia-text-muted)', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem' }}>
                    Error loading slide.
                  </div>
                )}
              </div>
            ) : (
              <div className="flex flex-col flex-1 relative overflow-hidden" style={{ background: 'var(--hestia-surface)' }}>
                {/* HTML Slide Renderer */}
                <div className="absolute inset-0 flex flex-col items-center p-8 bg-[var(--hestia-bg)] overflow-y-auto" style={{ background: 'var(--hestia-surface)' }}>

                  <div className="w-full max-w-[800px] flex flex-col mt-4 mb-8">
                    {/* Aspect Ratio Container for Slide */}
                    <div className="relative w-full shadow-lg shrink-0" style={{ paddingTop: '56.25%', borderRadius: '4px', overflow: 'hidden' }}>
                      <div className="absolute inset-0">
                        {renderSlideHtml(currentSlideIndex)}
                      </div>
                    </div>

                    {/* Speaker Notes */}
                    {currentSlideIndex > 0 && allCachedSlides[currentSlideIndex - 1]?.notes && (
                      <div className="mt-4 w-full p-5 rounded-lg border shrink-0 text-left" style={{ background: 'var(--hestia-bg)', borderColor: 'var(--hestia-border)' }}>
                        <h4 style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-primary)', textTransform: 'uppercase', marginBottom: '8px', letterSpacing: '0.05em' }}>
                          Speaker Notes
                        </h4>
                        <p style={{ fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)', whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>
                          {renderBoldPlaceholders(allCachedSlides[currentSlideIndex - 1].notes)}
                        </p>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )}

            {/* Navigation Strip */}
            {hasSlides && (
              <div
                className="h-12 shrink-0 flex items-center justify-between px-6 z-10"
                style={{
                  background: 'var(--hestia-surface)',
                  borderTop: '1px solid var(--hestia-border)'
                }}
              >
                <button
                  onClick={prevSlide}
                  disabled={currentSlideIndex === 0}
                  className="h-8 w-8 rounded-md flex items-center justify-center transition-colors disabled:opacity-30 focus:outline-none focus-visible:ring-2"
                  style={{ border: '1px solid var(--hestia-border)', color: 'var(--hestia-text)' }}
                  onMouseEnter={(e) => { if (currentSlideIndex > 0) e.currentTarget.style.background = 'var(--hestia-primary-muted)' }}
                  onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}
                >
                  <ChevronLeft className="h-4 w-4" />
                </button>

                {/* Dot indicators */}
                <div className="flex items-center gap-1.5 overflow-hidden px-4">
                  {(() => {
                    const WIN = 15;
                    let start = Math.max(0, currentSlideIndex - Math.floor(WIN / 2));
                    const end = Math.min(totalSlides, start + WIN);
                    start = Math.max(0, end - WIN);
                    return Array.from({ length: end - start }, (_, i) => start + i).map(idx => (
                      <button
                        key={idx}
                        onClick={() => setCurrentSlideIndex(idx)}
                        className="rounded-full transition-all shrink-0 focus:outline-none focus-visible:ring-2"
                        style={{
                          width: idx === currentSlideIndex ? '1.5rem' : '6px',
                          height: '6px',
                          background: idx === currentSlideIndex ? 'var(--hestia-primary)' : 'var(--hestia-border)',
                        }}
                        onMouseEnter={(e) => { if (idx !== currentSlideIndex) e.currentTarget.style.background = 'var(--hestia-primary-muted)' }}
                        onMouseLeave={(e) => { if (idx !== currentSlideIndex) e.currentTarget.style.background = 'var(--hestia-border)' }}
                      />
                    ));
                  })()}
                </div>

                <div className="flex items-center gap-3 shrink-0">
                  <span style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.875rem', color: 'var(--hestia-text-muted)' }}>
                    {currentSlideIndex + 1} / {totalSlides}
                  </span>
                  <button
                    onClick={nextSlide}
                    disabled={currentSlideIndex === totalSlides - 1}
                    className="h-8 w-8 rounded-md flex items-center justify-center transition-colors disabled:opacity-30 focus:outline-none focus-visible:ring-2"
                    style={{ border: '1px solid var(--hestia-border)', color: 'var(--hestia-text)' }}
                    onMouseEnter={(e) => { if (currentSlideIndex < totalSlides - 1) e.currentTarget.style.background = 'var(--hestia-primary-muted)' }}
                    onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}
                  >
                    <ChevronRight className="h-4 w-4" />
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
