import React, { useState, useEffect, useRef } from "react";
import { Presentation, Sparkles, Download, RefreshCw, X, Check, ChevronUp, ChevronDown, Code, Save } from "lucide-react";
import { toast } from "@/hooks/use-toast";
import type { LeaveGuard } from "@/hooks/useWizardState";
import { useSlideDeck, deckSlides, DeckSlide } from "@/hooks/useSlideDeck";
import { Button } from "@/components/ui/button";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { saveSlides, errorMessage, handleAuthError } from "@/lib/api";
import { TemplateUploadBox } from "./TemplateUploadBox";
import { SlidePreview, TitleSlidePreview, renderBoldPlaceholders } from "./slides/SlidePreview";
import { SlideEditorForm } from "./slides/SlideEditorForm";
import { SlidePrepTasks } from "./slides/SlidePrepTasks";
import { SlideNavigation } from "./slides/SlideNavigation";
import { WorkshopSession, WorkshopInput, LearningGoalPlan, SlideData, TemplateInfo } from "@/lib/workshop-generator";

interface Props {
  session: WorkshopSession;
  meta: WorkshopInput;
  goals: LearningGoalPlan[];
  slidesCache: Record<number, SlideData[]>;
  setSlidesCache: React.Dispatch<React.SetStateAction<Record<number, SlideData[]>>>;
  /** Ids of the ticked slide prep tasks */
  completedTasks: string[];
  onCompletedTasksChange: (tasks: string[]) => void;
  isOpen: boolean;
  setIsOpen: (open: boolean) => void;
  templateInfo?: TemplateInfo | null;
  setTemplateInfo?: (v: TemplateInfo | null) => void;
  /** Lets the wizard ask before navigating away while slide edits are unsaved. */
  registerLeaveGuard?: (guard: LeaveGuard | null) => void;
}

const sidebarHeading: React.CSSProperties = {
  fontFamily: "var(--hestia-font-mono)", fontSize: "0.75rem", fontWeight: 600, textTransform: "uppercase",
  letterSpacing: "0.08em", color: "var(--hestia-text-muted)",
};

/**
 * Preparation step: generate the slides of every block, preview and edit them, tick off each
 * slide's prep tasks, choose the PowerPoint template and download the deck.
 * Slide 0 is the title slide; slides 1… are `deckSlides(...)`.
 */
export function SlideWorkstation({ session, meta, goals, slidesCache, setSlidesCache, completedTasks, onCompletedTasksChange, isOpen, setIsOpen, templateInfo, setTemplateInfo, registerLeaveGuard }: Props) {
  const deck = useSlideDeck({ session, meta, goals, setSlidesCache, setTemplateInfo });
  const [currentSlideIndex, setCurrentSlideIndex] = useState(0);

  const [isEditMode, setIsEditMode] = useState(false);
  const [editableCache, setEditableCache] = useState<Record<number, SlideData[]>>({});
  const [isSavingEdits, setIsSavingEdits] = useState(false);

  const hasSlides = Object.keys(slidesCache).length > 0;
  const savedDeck = deckSlides(slidesCache, session.blocks.length);
  const shownDeck = isEditMode ? deckSlides(editableCache, session.blocks.length) : savedDeck;
  const totalSlides = hasSlides ? 1 + savedDeck.length : 0;
  const current: DeckSlide | undefined = currentSlideIndex > 0 ? shownDeck[currentSlideIndex - 1] : undefined;

  const startEditing = () => {
    setEditableCache(JSON.parse(JSON.stringify(slidesCache)));
    setIsEditMode(true);
  };

  const updateSlide = (entry: DeckSlide, patch: Partial<SlideData>) =>
    setEditableCache(prev => ({
      ...prev,
      [entry.blockIndex]: prev[entry.blockIndex].map((s, i) => (i === entry.slideIndex ? { ...s, ...patch } : s)),
    }));

  /** Saves the slide edits; resolves false (and stays in edit mode) if saving failed. */
  const handleSaveEdits = async (): Promise<boolean> => {
    setIsSavingEdits(true);
    try {
      if (!session.id) throw new Error("The session has not been saved yet");
      await saveSlides(session.id, editableRef.current);
      setSlidesCache(editableRef.current);
      // A download waiting on this save must see the saved slides before the next render
      savedRef.current = editableRef.current;
      toast({ title: "Slides Updated", description: "Your manual edits have been saved." });
      isEditModeRef.current = false;
      setIsEditMode(false);
      return true;
    } catch (e) {
      if (!handleAuthError(e)) toast({ title: "Save Failed", description: errorMessage(e, "Failed to save slides to server"), variant: "destructive" });
      return false;
    } finally {
      setIsSavingEdits(false);
    }
  };

  // ── Unsaved slide edits ───────────────────────────────────────────────
  // Edit mode works on a copy of the slides. Anything that would drop that copy (leaving the
  // step, View, Regenerate, Download) asks first: save, discard or stay.
  const isEditModeRef = useRef(isEditMode);
  isEditModeRef.current = isEditMode;
  const editableRef = useRef(editableCache);
  editableRef.current = editableCache;
  const savedRef = useRef(slidesCache);
  savedRef.current = slidesCache;
  const [leaveDialog, setLeaveDialog] = useState<{ proceed: () => void } | null>(null);

  const hasUnsavedEdits = () =>
    isEditModeRef.current && JSON.stringify(editableRef.current) !== JSON.stringify(savedRef.current);

  const leaveEditMode = () => {
    isEditModeRef.current = false;
    setIsEditMode(false);
  };

  /** Runs `proceed` now, or after asking what to do with unsaved slide edits. Leaves edit mode either way. */
  const confirmLeave = (proceed: () => void) => {
    if (hasUnsavedEdits()) {
      setLeaveDialog({ proceed });
    } else {
      if (isEditModeRef.current) leaveEditMode();
      proceed();
    }
  };

  // While slides are generated, leaving would drop them (or put them into another session): wait
  const isGeneratingRef = useRef(deck.isGenerating);
  isGeneratingRef.current = deck.isGenerating;
  useEffect(() => {
    registerLeaveGuard?.({
      isDirty: () => isGeneratingRef.current || hasUnsavedEdits(),
      confirmLeave: proceed => {
        if (isGeneratingRef.current) {
          toast({ title: "Slides are still being generated", description: "Please wait until they are ready before leaving this step." });
          return;
        }
        confirmLeave(proceed);
      },
    });
    return () => registerLeaveGuard?.(null);
  });

  // Ticked prep tasks are saved with the session (DraftState.completedTasks)
  const toggleSlideTask = (taskId: string) => {
    onCompletedTasksChange(completedTasks.includes(taskId)
      ? completedTasks.filter(t => t !== taskId)
      : [...completedTasks, taskId]);
  };

  const toggleOpen = () => setIsOpen(!isOpen);

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
      {/* HEADER (a div, since it contains buttons) */}
      <div
        role="button"
        tabIndex={0}
        aria-expanded={isOpen}
        onClick={toggleOpen}
        onKeyDown={e => {
          if (e.target === e.currentTarget && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); toggleOpen(); }
        }}
        className="w-full flex items-center gap-3 transition-colors text-left cursor-pointer"
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
        </div>
        <div className="flex items-center gap-2 mr-2">
          {hasSlides && deck.justGenerated && (
            <div className="hidden lg:flex items-center justify-center gap-1.5 px-2 py-1.5 rounded-md bg-emerald-50 border border-emerald-200 mr-1 transition-opacity duration-500">
              <Check className="h-3 w-3 text-emerald-600" />
              <span className="text-emerald-700" style={{ fontSize: '0.75rem', fontFamily: 'var(--hestia-font-mono)', fontWeight: 500 }}>
                {totalSlides} slides
              </span>
            </div>
          )}

          <button
            onClick={(e) => {
              e.stopPropagation();
              // Regenerating replaces the slides, so unsaved edits are settled first
              confirmLeave(() => deck.generate());
            }}
            disabled={deck.isGenerating || deck.isUploading}
            className={
              hasSlides
                ? "flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-md transition-colors focus:outline-none focus-visible:ring-2 disabled:opacity-50"
                : "flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-md transition-colors focus:outline-none focus-visible:ring-2 disabled:opacity-50 text-white"
            }
            style={
              hasSlides
                ? {
                  background: 'transparent',
                  border: '1px solid var(--hestia-border)',
                  color: 'var(--hestia-text)',
                  fontFamily: 'var(--hestia-font-body)'
                }
                : { fontFamily: 'var(--hestia-font-body)', background: 'var(--hestia-primary)' }
            }
            onMouseEnter={(e) => {
              if (hasSlides) e.currentTarget.style.background = 'var(--hestia-primary-muted)';
            }}
            onMouseLeave={(e) => {
              if (hasSlides) e.currentTarget.style.background = 'transparent';
            }}
          >
            {deck.isGenerating ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
            <span style={{ fontSize: '0.75rem', fontWeight: 600 }}>
              {deck.isGenerating ? "Generating…" : hasSlides ? "Regenerate" : "Generate"}
            </span>
          </button>

          {hasSlides && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                // The download uses the saved slides, so unsaved edits are settled first
                confirmLeave(() => deck.download(deckSlides(savedRef.current, session.blocks.length).map(d => d.slide)));
              }}
              disabled={deck.isDownloading}
              className="flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-md transition-colors focus:outline-none focus-visible:ring-2 disabled:opacity-50 text-white"
              style={{
                background: 'var(--hestia-primary)',
                border: 'none',
                fontFamily: 'var(--hestia-font-body)'
              }}
            >
              {deck.isDownloading ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />}
              <span style={{ fontSize: '0.75rem', fontWeight: 600 }}>
                {deck.isDownloading ? "Exporting…" : "Download"}
              </span>
            </button>
          )}

          {hasSlides && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                if (isEditMode) confirmLeave(() => { });
                else startEditing();
                if (!isOpen && !isEditMode) setIsOpen(true);
              }}
              disabled={deck.isGenerating || deck.isUploading || isSavingEdits}
              className="flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-md transition-colors focus:outline-none focus-visible:ring-2 border border-emerald-200 text-emerald-700 bg-white hover:bg-emerald-50"
              style={{ fontFamily: 'var(--hestia-font-body)' }}
            >
              <Code className="h-4 w-4" />
              <span style={{ fontSize: '0.75rem', fontWeight: 600 }}>
                {isEditMode ? "View" : "Edit"}
              </span>
            </button>
          )}
        </div>
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
      </div>

      {/* BODY */}
      {isOpen && (
        <div className="flex flex-col lg:flex-row" style={{ borderTop: '1px solid var(--hestia-border)' }}>

          {/* LEFT SIDEBAR */}
          <div className="shrink-0 p-5 flex flex-col gap-6" style={{ width: '220px', borderRight: '1px solid var(--hestia-border)', background: 'var(--hestia-surface)' }}>
            <div className="space-y-3">
              <h4 style={sidebarHeading}>PowerPoint Template</h4>
              <TemplateUploadBox
                fileName={templateInfo ? (templateInfo.name ?? null) || (templateInfo.source === "COURSE" ? "Course template" : "Saved Template") : null}
                caption={templateInfo?.source === "COURSE" ? "From course template" : "Click to replace"}
                isBusy={deck.isUploading}
                onUpload={deck.uploadTemplate}
                onRemove={deck.removeTemplate}
                removeTitle={templateInfo?.source === "COURSE" ? "Use the default design for this session" : "Remove template"}
                note={templateInfo?.source === "COURSE" ? "Removing it here only affects this session." : undefined}
              />
            </div>

            {hasSlides && (
              <>
                <div style={{ height: '1px', background: 'var(--hestia-border)', margin: '12px 0' }} />
                <div className="space-y-3">
                  <h4 style={sidebarHeading}>Todos</h4>
                  <SlidePrepTasks
                    slide={savedDeck[currentSlideIndex - 1]?.slide}
                    slideIndex={currentSlideIndex}
                    block={current ? session.blocks[current.blockIndex] : undefined}
                    completedTasks={completedTasks}
                    onToggle={toggleSlideTask}
                  />
                </div>
              </>
            )}
          </div>

          {/* RIGHT: Preview or editor */}
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
                <div className="flex items-center justify-between px-4 h-12 shrink-0" style={{ borderBottom: '1px solid var(--hestia-border)' }}>
                  <span style={{ fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', fontWeight: 600, color: 'var(--hestia-text)' }}>Slide Editor</span>
                  <div className="flex items-center gap-2">
                    <button
                      onClick={leaveEditMode}
                      disabled={isSavingEdits}
                      className="px-3 py-1.5 flex items-center gap-1.5 rounded-md transition-colors focus:outline-none focus-visible:ring-2 disabled:opacity-50"
                      style={{ fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                      onMouseEnter={(e) => e.currentTarget.style.background = 'var(--hestia-primary-muted)'}
                      onMouseLeave={(e) => e.currentTarget.style.background = 'transparent'}
                    >
                      <X className="h-4 w-4" /> Cancel
                    </button>
                    <button
                      onClick={() => { handleSaveEdits(); }}
                      disabled={isSavingEdits}
                      className="px-3 py-1.5 flex items-center gap-1.5 rounded-md transition-colors text-white focus:outline-none focus-visible:ring-2 disabled:opacity-50"
                      style={{ background: 'var(--hestia-primary)', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', fontWeight: 500 }}
                      onMouseEnter={(e) => e.currentTarget.style.background = 'var(--hestia-primary-hover)'}
                      onMouseLeave={(e) => e.currentTarget.style.background = 'var(--hestia-primary)'}
                    >
                      {isSavingEdits ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
                      Save
                    </button>
                  </div>
                </div>

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
                ) : current ? (
                  <SlideEditorForm slide={current.slide} onChange={patch => updateSlide(current, patch)} />
                ) : (
                  <div className="flex-1 flex items-center justify-center" style={{ color: 'var(--hestia-text-muted)', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem' }}>
                    Error loading slide.
                  </div>
                )}
              </div>
            ) : (
              <div className="flex flex-col flex-1 relative overflow-hidden" style={{ background: 'var(--hestia-surface)' }}>
                <div className="absolute inset-0 flex flex-col items-center p-8 bg-[var(--hestia-bg)] overflow-y-auto" style={{ background: 'var(--hestia-surface)' }}>
                  <div className="w-full max-w-[800px] flex flex-col mt-4 mb-8">
                    {/* 16:9 slide */}
                    <div className="relative w-full shadow-lg shrink-0" style={{ paddingTop: '56.25%', borderRadius: '4px', overflow: 'hidden' }}>
                      <div className="absolute inset-0">
                        {currentSlideIndex === 0 ? <TitleSlidePreview title={session.title} /> : current && <SlidePreview slide={current.slide} />}
                      </div>
                    </div>

                    {current?.slide.notes && (
                      <div className="mt-4 w-full p-5 rounded-lg border shrink-0 text-left" style={{ background: 'var(--hestia-bg)', borderColor: 'var(--hestia-border)' }}>
                        <h4 style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-primary)', textTransform: 'uppercase', marginBottom: '8px', letterSpacing: '0.05em' }}>
                          Speaker Notes
                        </h4>
                        <p style={{ fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)', whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>
                          {renderBoldPlaceholders(current.slide.notes)}
                        </p>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )}

            {hasSlides && <SlideNavigation current={currentSlideIndex} total={totalSlides} onChange={setCurrentSlideIndex} />}
          </div>
        </div>
      )}
      <AlertDialog open={leaveDialog !== null} onOpenChange={(open) => { if (!open && !isSavingEdits) setLeaveDialog(null); }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Your slide edits are not saved</AlertDialogTitle>
            <AlertDialogDescription>
              Save your changes to the slides before you continue, or discard them.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={isSavingEdits} onClick={() => setLeaveDialog(null)}>Stay</AlertDialogCancel>
            <Button
              variant="outline"
              disabled={isSavingEdits}
              className="text-destructive hover:text-destructive"
              onClick={() => {
                const proceed = leaveDialog?.proceed;
                setLeaveDialog(null);
                leaveEditMode();
                proceed?.();
              }}
            >
              Discard changes
            </Button>
            <Button
              disabled={isSavingEdits}
              onClick={async () => {
                const proceed = leaveDialog?.proceed;
                const ok = await handleSaveEdits();
                if (!ok) return; // keep the dialog and the edits; the error was shown as a toast
                setLeaveDialog(null);
                proceed?.();
              }}
            >
              {isSavingEdits ? <><RefreshCw className="h-4 w-4 mr-2 animate-spin" /> Saving…</> : "Save and continue"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
