import React, { useState, useEffect } from "react";
import { Presentation, Upload, Sparkles, Download, RefreshCw, X, File as FileIcon, ChevronLeft, ChevronRight, Check, ChevronUp, ChevronDown } from "lucide-react";
import { Button } from "@/components/ui/button";
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

  const [previewImages, setPreviewImages] = useState<string[]>([]);
  const [currentSlideIndex, setCurrentSlideIndex] = useState(0);
  const [isLoadingPreviews, setIsLoadingPreviews] = useState(false);

  const hasSlides = Object.keys(slidesCache).length > 0;

  useEffect(() => {
    if (hasSlides && isOpen) {
      fetchPreviews();
    }
  }, [hasSlides, isOpen]);

  const fetchPreviews = async () => {
    setIsLoadingPreviews(true);
    try {
      const res = await fetch(import.meta.env.BASE_URL + `api/workshop/sessions/${session.id}/slides/preview`);
      if (!res.ok) throw new Error("Failed to load previews");
      const data = await res.json();
      setPreviewImages(data.images || []);
      setCurrentSlideIndex(0);
    } catch (e) {
      console.error(e);
      setPreviewImages([]);
    } finally {
      setIsLoadingPreviews(false);
    }
  };

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

      if (hasSlides) {
        await fetchPreviews();
      }
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
    let hasError = false;

    try {
      for (let i = 0; i < session.blocks.length; i++) {
        const block = session.blocks[i];
        const res = await fetch(import.meta.env.BASE_URL + "api/workshop/export/block-slides", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ block, meta }),
        });
        if (!res.ok) throw new Error("Failed to generate slides for block " + block.phaseLabel);
        const slides = await res.json();
        newCache[i] = slides;
      }
      setSlidesCache(newCache);

      await fetch(import.meta.env.BASE_URL + `api/workshop/sessions/${session.id}/slides`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(newCache),
      });

      toast({ title: "Slides Generated!", description: "Scroll right to preview your deck." });
      await fetchPreviews();
    } catch (e) {
      hasError = true;
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
      // Fallback to the multipart form endpoint
      try {
        const formData = new FormData();
        formData.append("session", JSON.stringify(session));
        formData.append("meta", JSON.stringify(meta));
        const allCachedSlides = session.blocks.flatMap((_, i) => slidesCache[i] ?? []);
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

  const nextSlide = () => setCurrentSlideIndex(i => Math.min(previewImages.length - 1, i + 1));
  const prevSlide = () => setCurrentSlideIndex(i => Math.max(0, i - 1));

  return (
    <div className={`border rounded-xl shadow-sm overflow-hidden mb-6 transition-all duration-300 ${hasSlides ? "border-emerald-400 bg-white dark:bg-zinc-900" : "border-border/60 bg-card"}`}>
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center gap-3 p-4 text-left border-b border-border/40 bg-transparent hover:bg-muted/10 transition-colors"
      >
        <div className={`h-9 w-9 rounded-full flex items-center justify-center text-lg shrink-0 ${hasSlides ? "bg-emerald-100" : "bg-primary/10"}`}>
          <Presentation className={`h-5 w-5 ${hasSlides ? "text-emerald-600" : "text-primary"}`} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <h3 className="font-display text-base font-bold leading-tight">Slide Workstation</h3>
            {hasSlides && (
              <span className="text-[10px] font-bold bg-emerald-100 text-emerald-700 px-2 py-0.5 rounded-full border border-emerald-200 uppercase tracking-wide">
                ✓ Slides Generated
              </span>
            )}
          </div>
          <p className="text-xs text-foreground/80 mt-0.5">
            {hasSlides ? "Ready to preview and download" : "Generate presentation slides for this session"}
          </p>
        </div>
        <div className="text-muted-foreground">
          {isOpen ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
        </div>
      </button>

      {isOpen && (
        <div className="flex flex-col lg:flex-row divide-y lg:divide-y-0 lg:divide-x divide-border/40">
          {/* LEFT: Controls */}
          <div className="lg:w-64 shrink-0 p-4 flex flex-col gap-3 bg-muted/10">
            {/* Template Upload */}
            <div className="space-y-1.5">
              <h4 className="text-[10px] font-bold text-muted-foreground uppercase tracking-wider font-body">
                PowerPoint Template
              </h4>
              <div className="relative group">
                <input
                  type="file"
                  accept=".pptx"
                  onChange={handleTemplateUpload}
                  className="absolute inset-0 w-full h-full opacity-0 cursor-pointer z-10"
                />
                <div className={`border-2 border-dashed rounded-lg p-3 flex flex-col items-center justify-center gap-1 transition-all min-h-[72px]
                ${template
                    ? "border-emerald-400/60 bg-emerald-50/50 dark:bg-emerald-950/20"
                    : "border-border bg-background group-hover:border-primary/40 group-hover:bg-primary/5"
                  }`}
                >
                  {isUploading ? (
                    <RefreshCw className="h-5 w-5 text-primary animate-spin" />
                  ) : template ? (
                    <>
                      <div className="h-8 w-8 rounded-md bg-emerald-100 dark:bg-emerald-900/30 flex items-center justify-center">
                        <FileIcon className="h-4 w-4 text-emerald-600" />
                      </div>
                      <span className="text-[11px] font-medium text-emerald-700 dark:text-emerald-400 text-center truncate w-full px-1 font-body">{template.name}</span>
                      <span className="text-[9px] text-emerald-600/70 font-body">Click to replace</span>
                    </>
                  ) : (
                    <>
                      <Upload className="h-5 w-5 text-muted-foreground group-hover:text-primary transition-colors" />
                      <span className="text-[11px] text-muted-foreground font-medium text-center font-body">Upload .pptx template</span>
                      <span className="text-[10px] text-muted-foreground/60 font-body">Optional — applies branding</span>
                    </>
                  )}
                </div>
              </div>
            </div>

            {/* Actions */}
            <div className="space-y-1.5">
              <h4 className="text-[10px] font-bold text-muted-foreground uppercase tracking-wider font-body">Actions</h4>

              {hasSlides && (
                <div className="flex items-center gap-1.5 text-[11px] font-medium text-emerald-600 bg-emerald-50 dark:bg-emerald-950/30 border border-emerald-200 dark:border-emerald-800/50 px-2.5 py-1.5 rounded-md font-body">
                  <Check className="h-3 w-3" />
                  {Object.values(slidesCache).flat().length} slides generated
                </div>
              )}

              <Button
                onClick={handleGenerate}
                disabled={isGenerating || isUploading}
                className="w-full gap-2 font-body"
                size="sm"
              >
                {isGenerating
                  ? <RefreshCw className="h-3.5 w-3.5 animate-spin" />
                  : <Sparkles className="h-3.5 w-3.5" />
                }
                {isGenerating ? "Generating…" : hasSlides ? "Regenerate Slides" : "Generate Slides"}
              </Button>

              {hasSlides && (
                <Button
                  onClick={handleDownload}
                  disabled={isSaving}
                  variant="outline"
                  className="w-full gap-2 font-body"
                  size="sm"
                >
                  {isSaving
                    ? <RefreshCw className="h-3.5 w-3.5 animate-spin" />
                    : <Download className="h-3.5 w-3.5" />
                  }
                  {isSaving ? "Exporting…" : "Download PPTX"}
                </Button>
              )}
            </div>
          </div>

          {/* RIGHT: Preview */}
          <div className="flex-1 flex flex-col min-h-[500px] bg-background">
            {isLoadingPreviews ? (
              <div className="flex-1 flex flex-col items-center justify-center gap-3 text-muted-foreground p-8">
                <RefreshCw className="h-8 w-8 animate-spin text-primary/50" />
                <p className="text-sm font-body">Rendering previews…</p>
              </div>
            ) : previewImages.length > 0 ? (
              <div className="flex flex-col">
                <div className="bg-muted/20 border-b border-border/40 flex items-center justify-center overflow-hidden">
                  <img
                    src={previewImages[currentSlideIndex]}
                    alt={`Slide ${currentSlideIndex + 1}`}
                    className="w-auto h-auto max-w-full max-h-[500px] block"
                  />
                </div>

                {/* Navigation */}
                <div className="h-8 border-t border-border/40 flex items-center justify-between px-4 bg-muted/10 shrink-0">
                  <button
                    onClick={prevSlide}
                    disabled={currentSlideIndex === 0}
                    className="h-7 w-7 rounded-md border border-border/60 flex items-center justify-center disabled:opacity-30 hover:bg-muted transition-colors shrink-0"
                  >
                    <ChevronLeft className="h-4 w-4" />
                  </button>

                  {/* Dots — sliding window of 15 centred on the current slide */}
                  <div className="flex items-center gap-1 overflow-hidden">
                    {(() => {
                      const total = previewImages.length;
                      const WIN = 15;
                      let start = Math.max(0, currentSlideIndex - Math.floor(WIN / 2));
                      const end = Math.min(total, start + WIN);
                      start = Math.max(0, end - WIN); // clamp start if near end
                      return Array.from({ length: end - start }, (_, i) => start + i).map(idx => (
                        <button
                          key={idx}
                          onClick={() => setCurrentSlideIndex(idx)}
                          className={`rounded-full transition-all shrink-0 ${idx === currentSlideIndex
                            ? "w-4 h-1.5 bg-primary"
                            : "w-1.5 h-1.5 bg-border hover:bg-muted-foreground"
                            }`}
                        />
                      ));
                    })()}
                  </div>

                  <div className="flex items-center gap-2 shrink-0">
                    <span className="text-[11px] text-muted-foreground font-body tabular-nums">
                      {currentSlideIndex + 1} / {previewImages.length}
                    </span>
                    <button
                      onClick={nextSlide}
                      disabled={currentSlideIndex === previewImages.length - 1}
                      className="h-7 w-7 rounded-md border border-border/60 flex items-center justify-center disabled:opacity-30 hover:bg-muted transition-colors"
                    >
                      <ChevronRight className="h-4 w-4" />
                    </button>
                  </div>
                </div>
              </div>
            ) : (
              <div className="flex-1 flex flex-col items-center justify-center gap-3 p-8 text-center">
                <div className="h-16 w-16 rounded-xl bg-muted/50 flex items-center justify-center">
                  <Presentation className="h-8 w-8 text-muted-foreground/40" />
                </div>
                <div>
                  <p className="text-sm font-display font-semibold text-muted-foreground">No slides yet</p>
                  <p className="text-[11px] text-muted-foreground/70 mt-0.5 font-body">
                    Optionally upload a template, then click <strong>Generate Slides</strong>.
                  </p>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
