import React, { useState, useEffect, useRef } from "react";
import {
  Presentation,
  Sparkles,
  Download,
  RefreshCw,
  X,
  ChevronLeft,
  ChevronRight,
  Check,
  ChevronUp,
  ChevronDown,
  Code,
  Save,
} from "lucide-react";
import { toast } from "@/hooks/use-toast";
import type { LeaveGuard } from "@/hooks/useWizardState";
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
import { saveAs } from "file-saver";
import {
  deleteSessionTemplate, uploadSessionTemplate, generateBlockSlides, saveSlides, exportPptxWithTemplate,
  errorMessage, handleAuthError, safeFileName,
} from "@/lib/api";
import { TemplateUploadBox } from "./TemplateUploadBox";
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

export function SlideWorkstation({ session, meta, goals, slidesCache, setSlidesCache, completedTasks, onCompletedTasksChange, isOpen, setIsOpen, templateInfo, setTemplateInfo, registerLeaveGuard }: Props) {
  const [isGenerating, setIsGenerating] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [isUploading, setIsUploading] = useState(false);

  const [currentSlideIndex, setCurrentSlideIndex] = useState(0);

  const [isEditMode, setIsEditMode] = useState(false);
  const [editableCache, setEditableCache] = useState<Record<number, SlideData[]>>({});
  const [isSavingEdits, setIsSavingEdits] = useState(false);
  const [showSlidesGenerated, setShowSlidesGenerated] = useState(false);

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

  const handleTemplateUpload = async (file: File) => {
    if (!session.id) return;
    setIsUploading(true);
    try {
      await uploadSessionTemplate(session.id, file);
      setTemplateInfo?.({ source: "SESSION", name: file.name });
      toast({ title: "Template Saved", description: "Your branding template has been uploaded." });
    } catch (err) {
      toast({ title: "Upload Failed", description: err instanceof Error ? err.message : "Error", variant: "destructive" });
    } finally {
      setIsUploading(false);
    }
  };

  /** Removes the template from this session only; a course template stays for the course's other sessions. */
  const handleTemplateRemove = async () => {
    if (!session.id) return;
    setIsUploading(true);
    try {
      await deleteSessionTemplate(session.id);
      setTemplateInfo?.(null);
      toast({ title: "Template removed", description: "This session now uses the default design." });
    } catch {
      toast({ title: "Failed to remove template", variant: "destructive" });
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
          // Skip BUFFER blocks — no slides generated for these
          if (block.phase === "BUFFER") {
            return { idx: actualIdx, slides: [] };
          }
          try {
            return { idx: actualIdx, slides: await generateBlockSlides(block, meta, goals) };
          } catch (e) {
            throw handleAuthError(e) ? e : new Error("Failed to generate slides for block " + block.phaseLabel);
          }
        });

        const results = await Promise.all(promises);
        for (const { idx, slides } of results) {
          newCache[idx] = slides;
        }
      }
      setSlidesCache(newCache);

      if (session.id) await saveSlides(session.id, newCache);

      toast({ title: "Slides Generated!", description: "Scroll right to preview your deck." });
      setShowSlidesGenerated(true);
      setTimeout(() => setShowSlidesGenerated(false), 4000);
    } catch (e) {
      if (!handleAuthError(e)) toast({ title: "Generation failed", description: errorMessage(e, "Unknown error"), variant: "destructive" });
    } finally {
      setIsGenerating(false);
    }
  };

  const handleDownload = async () => {
    setIsSaving(true);
    toast({ title: "Preparing Download…" });
    try {
      // The server uses the template stored for this session (or its course)
      const blob = await exportPptxWithTemplate(session, meta, allCachedSlides);
      saveAs(blob, `${safeFileName(session.title)}_slides.pptx`);
      toast({ title: "Downloaded!", description: "Your slide deck is ready." });
    } catch (err) {
      if (!handleAuthError(err)) toast({ title: "Download Failed", description: errorMessage(err, "Assembly failed"), variant: "destructive" });
    } finally {
      setIsSaving(false);
    }
  };

  const handleEditSlide = () => {
    setEditableCache(JSON.parse(JSON.stringify(slidesCache)));
    setIsEditMode(true);
  };

  /** Saves the slide edits; resolves false (and stays in edit mode) if saving failed. */
  const handleSaveEdits = async (): Promise<boolean> => {
    setIsSavingEdits(true);
    try {
      if (!session.id) throw new Error("The session has not been saved yet");
      await saveSlides(session.id, editableRef.current);
      setSlidesCache(editableRef.current);
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

  useEffect(() => {
    registerLeaveGuard?.({ isDirty: hasUnsavedEdits, confirmLeave });
    return () => registerLeaveGuard?.(null);
  });

  const nextSlide = () => setCurrentSlideIndex(i => Math.min(totalSlides - 1, i + 1));
  const prevSlide = () => setCurrentSlideIndex(i => Math.max(0, i - 1));

  // Ticked prep tasks are saved with the session (DraftState.completedTasks)
  const completedSlideTasks = new Set(completedTasks);
  const toggleSlideTask = (taskId: string) => {
    onCompletedTasksChange(completedSlideTasks.has(taskId)
      ? completedTasks.filter(t => t !== taskId)
      : [...completedTasks, taskId]);
  };

  let currentBlockIndex = -1;
  let currentSlideWithinBlock = -1;
  if (currentSlideIndex > 0) {
    let flatIndex = 0;
    for (let i = 0; i < session.blocks.length; i++) {
      const cacheToUse = isEditMode ? editableCache : slidesCache;
      const blockSlides = cacheToUse[i] || [];
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
   * Coerce any value the server might return in a string field to an actual string.
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

  const cleanBullet = (text: unknown) => {
    const str = coerceToString(text);
    if (!str) return "";
    return str.trim().replace(/^([a-zA-Z][\.\)]|[0-9]+[\.\)]|[-•])\s*/, "");
  };

  const renderBoldPlaceholders = (text: unknown) => {
    const str = cleanBullet(text);
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
    // ── Template colours ─────────────────────────────────────────────────────
    const CREAM = '#F8F7F6';
    const CREAM2 = '#DFDEDD';
    const DARK = '#2C2420';
    const MUTED = '#6B6663';
    const GREEN = '#059669'; // pair / small group
    const BLUE = '#2563EB'; // individual / Q&A
    const ORANGE = '#EA580C'; // whole class
    const PURPLE = '#6D28D9'; // lecture / reflect
    const BROWN = '#865C1D'; // title / brand

    const titleFont = "'Playfair Display', 'Georgia', serif";
    const bodyFont = "'Inter', 'system-ui', sans-serif";

    // ── Title slide (index 0) ────────────────────────────────────────────────
    if (index === 0) {
      return (
        <div style={{ background: CREAM, width: '100%', height: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'center', padding: '0 48px', position: 'relative', overflow: 'hidden' }}>
          {/* left accent stripe */}
          <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 8, background: BROWN }} />
          {/* eyebrow */}
          <div style={{ fontFamily: bodyFont, fontSize: '1.1rem', fontWeight: 600, color: BROWN, textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 12, paddingLeft: 8 }}>
            WORKSHOP SESSION
          </div>
          {/* title */}
          <h1 style={{ fontFamily: titleFont, fontSize: '3rem', fontWeight: 700, color: DARK, lineHeight: 1.2, marginBottom: 20, paddingLeft: 8 }}>
            {session.title || 'Workshop Session'}
          </h1>
          <div style={{ height: 3, width: 48, background: GREEN, marginLeft: 8 }} />
        </div>
      );
    }

    const slideData = allCachedSlides[index - 1];
    if (!slideData) return null;

    // ── Determine accent colour from layout / activity type ─────────────────
    const layout = slideData.layout || '';
    const titleLow = (slideData.title || '').toLowerCase();

    let accent = GREEN;
    if (layout === 'lecture_placeholder' || layout === 'debrief') accent = PURPLE;
    else if (layout === 'live_poll' || layout === 'activity_qanda' || layout === 'activity_q&a') accent = ORANGE;
    else if (titleLow.includes('debate') || titleLow.includes('brainstorm') || titleLow.includes('quiz') || titleLow.includes('poll')) accent = ORANGE;
    else if (layout === 'activity_sidebar' && (titleLow.includes('hands') || titleLow.includes('worked'))) accent = BLUE;

    // ── Shared slide shell ──────────────────────────────────────────────────
    const shell = (children: React.ReactNode, extraBg?: string) => {
      let displayTitle = slideData.title || 'Slide';
      let activityType: string | null = (slideData as any).activityName || null;
      let isActivityLayout = false;

      if (layout && (layout.startsWith('activity_') || layout === 'live_poll' || layout === 'debrief')) {
        isActivityLayout = true;
        const colonIdx = displayTitle.indexOf(':');
        if (colonIdx > 0 && colonIdx < displayTitle.length - 1) {
          if (!activityType) {
            activityType = displayTitle.substring(0, colonIdx).trim();
          }
          displayTitle = displayTitle.substring(colonIdx + 1).trim();
        }
      }

      const group = (slideData as any).group;
      const rawTopic = (slideData as any).topic || slideData.subtitle || displayTitle;
      const topicVal = rawTopic.replace(/\s*-\s*LG\s*\d+/i, '').trim();

      if (layout === 'debrief' || activityType === 'DEBRIEF' || activityType === 'REFLECT') {
        activityType = '🔄 DEBRIEF';
      }

      let headerText = slideData.subtitle || 'Phase';
      if (group === 'welcome') {
        headerText = '👋 WELCOME';
      } else if (group === 'agenda') {
        headerText = '🗂️ AGENDA';
      } else if (group === 'activate_prior_knowledge') {
        headerText = isActivityLayout ? 'ACTIVATE' : '📖 LECTURE';
      } else if (group === 'main_lecture') {
        if (layout === 'debrief') {
          headerText = topicVal || 'DEBRIEF';
        } else if (isActivityLayout) {
          headerText = topicVal || 'ACTIVITY';
        } else {
          headerText = '📖 LECTURE';
        }
      } else if (group === 'check_understanding') {
        headerText = 'CHECK UNDERSTANDING';
      } else if (group === 'summary') {
        headerText = '📝 SUMMARY';
      }

      return (
        <div style={{ background: extraBg || CREAM, width: '100%', height: '100%', display: 'flex', flexDirection: 'column', position: 'relative', overflow: 'hidden' }}>
          {/* left accent stripe */}
          <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: 6, background: accent }} />
          {/* header */}
          <div style={{ paddingLeft: 20, paddingRight: 16, paddingTop: 12, paddingBottom: 8, borderBottom: `1px solid ${CREAM2}`, flexShrink: 0 }}>
            <div style={{ fontFamily: bodyFont, fontSize: '0.65rem', fontWeight: 700, color: MUTED, textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 2 }}>
              {activityType
                ? <><span style={{ color: accent, fontWeight: 800 }}>{activityType}</span> - {headerText.toUpperCase()}</>
                : <>{headerText.toUpperCase()}</>
              }
            </div>
            {!isActivityLayout && (
              <h2 style={{ fontFamily: titleFont, fontSize: '1.6rem', fontWeight: 700, color: DARK, lineHeight: 1.2, margin: 0 }}>
                {layout === 'lecture_placeholder' && !displayTitle.includes('[Placeholder]') ? `[Placeholder] ${displayTitle}` : displayTitle}
              </h2>
            )}
          </div>
          {/* body */}
          <div style={{ flex: 1, paddingLeft: 20, paddingRight: 16, paddingTop: 10, paddingBottom: 10, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
            {children}
          </div>
        </div>
      )
    };

    // ── Agenda / content bullets ─────────────────────────────────────────────
    if (layout === 'agenda' || slideData.group === 'agenda') {
      return shell(
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16, marginTop: 24 }}>
          {slideData.bullets?.map((b, i) => (
            <div key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: 16 }}>
              <span style={{ fontFamily: bodyFont, fontSize: '1.5rem', fontWeight: 700, color: accent }}>{i + 1}</span>
              <span style={{ fontFamily: bodyFont, fontSize: '1.25rem', color: DARK, lineHeight: 1.4, marginTop: 4 }}>
                {renderBoldPlaceholders(b)}
              </span>
            </div>
          ))}
        </div>
      );
    }

    // ── Think-Pair-Share / Worked Problem (activity_grid3) ──────────────────
    if (layout === 'activity_grid3') {
      const steps = slideData.activityInstructions && slideData.activityInstructions.length > 0
        ? slideData.activityInstructions.slice(0, 3)
        : ['[THINK (2 min)]', '[PAIR (2 min)]', '[SHARE (1 min)]'];
      return shell(
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, height: '100%' }}>
          <div style={{ background: CREAM2, borderRadius: 4, padding: '10px 14px', borderLeft: `3px solid ${accent}` }}>
            <p style={{ fontFamily: bodyFont, fontSize: '1.2rem', color: DARK, lineHeight: 1.4, margin: 0 }}>
              {slideData.activityPrompt || 'Activity prompt'}
            </p>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 4, flex: 1 }}>
            {steps.map((step, i) => {
              const cleanStep = step.replace(/^\d+[\.\)]\s*/, '');
              return (
                <div key={i} style={{ background: accent, borderRadius: 4, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '14px', textAlign: 'center' }}>
                  <span style={{ fontFamily: bodyFont, fontSize: '1.05rem', fontWeight: 600, color: '#fff', lineHeight: 1.3 }}>{renderBoldPlaceholders(cleanStep)}</span>
                </div>
              );
            })}
          </div>
        </div>
      );
    }

            // ── Activity (activity_sidebar) ──────────────────────────────────────────
    if (layout === 'activity_sidebar') {
      return shell(
        <div style={{ display: 'flex', gap: 12, height: '100%' }}>
          {/* Main Area (Prompt) */}
          <div style={{ flex: 2, display: 'flex', alignItems: 'center', background: CREAM2, borderRadius: 4, padding: '16px 24px', borderLeft: `3px solid ${accent}` }}>
            <p style={{ fontFamily: bodyFont, fontSize: '1.4rem', color: DARK, lineHeight: 1.5, margin: 0, fontWeight: 500 }}>
              {slideData.activityPrompt || 'Discussion prompt'}
            </p>
          </div>
          {/* Sidebar (Logistics) */}
          <div style={{ flex: 1, background: CREAM2, borderRadius: 4, padding: '16px 16px', display: 'flex', flexDirection: 'column', gap: 10 }}>
            <div style={{ fontFamily: bodyFont, fontSize: '0.9rem', fontWeight: 700, color: accent, textTransform: 'uppercase', display: 'flex', alignItems: 'center', gap: 6 }}>
              <span>🛠️</span> LOGISTICS
            </div>
            <ul style={{ margin: 0, paddingLeft: 18, listStyle: 'disc' }}>
              {(slideData.activityInstructions || []).map((inst, i) => {
                const cleanInst = inst.replace(/^\d+[\.\)]\s*/, '');
                return <li key={i} style={{ fontFamily: bodyFont, fontSize: '1.1rem', color: DARK, marginBottom: 8, lineHeight: 1.4 }}>{renderBoldPlaceholders(cleanInst)}</li>;
              })}
            </ul>
          </div>
        </div>
      );
    }

    // ── Q&A ─────────────────────────────────────────────────────────────────
    if (layout === 'activity_qanda' || layout === 'activity_q&a') {
      return shell(
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', gap: 12 }}>
          <div style={{ fontSize: '3rem' }}>🙋</div>
          <p style={{ fontFamily: titleFont, fontSize: '1.6rem', fontWeight: 700, color: DARK, textAlign: 'center', margin: 0 }}>
            {slideData.activityPrompt || 'What questions do you have?'}
          </p>
          <p style={{ fontFamily: bodyFont, fontSize: '0.85rem', color: MUTED, textAlign: 'center', margin: 0 }}>
            {slideData.notes || ''}
          </p>
        </div>
      );
    }

    // ── Quiz / Poll ──────────────────────────────────────────────────────────
    if (layout === 'live_poll') {
      const letters = ['A', 'B', 'C', 'D'];
      return shell(
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, height: '100%' }}>
          <p style={{ fontFamily: titleFont, fontSize: '1.35rem', fontWeight: 700, color: DARK, lineHeight: 1.3, margin: 0, borderLeft: `3px solid ${ORANGE}`, paddingLeft: 10 }}>
            {slideData.pollQuestion || 'Poll question'}
          </p>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 5, flex: 1 }}>
            {(slideData.pollOptions || []).map((opt, i) => (
              <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, background: CREAM2, borderRadius: 4, padding: '6px 10px' }}>
                <span style={{ fontFamily: bodyFont, fontSize: '0.95rem', fontWeight: 800, color: '#fff', background: ORANGE, borderRadius: 3, padding: '2px 7px', flexShrink: 0 }}>{letters[i]}</span>
                <span style={{ fontFamily: bodyFont, fontSize: '0.95rem', color: DARK }}>{renderBoldPlaceholders(opt)}</span>
              </div>
            ))}
          </div>
        </div>
      );
    }

    // ── Lecture placeholder ──────────────────────────────────────────────────
    if (layout === 'lecture_placeholder') {
      return shell(
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', gap: 10 }}>
          <div style={{ fontSize: '2.8rem' }}>📖</div>
          <p style={{ fontFamily: bodyFont, fontSize: '0.85rem', fontWeight: 600, color: PURPLE, textTransform: 'uppercase', letterSpacing: '0.08em', margin: 0 }}>INSTRUCTOR LECTURE CONTENT</p>
          <div style={{ background: CREAM2, borderRadius: 6, padding: '10px 18px', textAlign: 'center', borderLeft: `3px solid ${PURPLE}`, maxWidth: '80%' }}>
            <p style={{ fontFamily: bodyFont, fontSize: '0.9rem', color: MUTED, margin: 0, lineHeight: 1.4 }}>
              {slideData.bullets?.[0] ?? 'Add your own slides for this section'}
            </p>
          </div>
        </div>
      );
    }

    // ── Debrief / Reflect ────────────────────────────────────────────────────
    if (layout === 'debrief') {
      const misconceptions = slideData.commonMisconceptions || [];
      
      return shell(
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12, height: '100%' }}>
          
          
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {/* Suggested answer */}
            {slideData.suggestedAnswer && (
              <div style={{ background: CREAM2, borderRadius: 4, padding: '8px 12px', borderLeft: `3px solid ${PURPLE}` }}>
                <div style={{ fontFamily: bodyFont, fontSize: '0.75rem', fontWeight: 700, color: PURPLE, textTransform: 'uppercase', marginBottom: 4 }}>SUGGESTED ANSWER</div>
                <p style={{ fontFamily: bodyFont, fontSize: '0.9rem', color: DARK, margin: 0, lineHeight: 1.4 }}>
                  {slideData.suggestedAnswer}
                </p>
              </div>
            )}
            {/* Misconceptions */}
            {misconceptions.length > 0 && (
              <div style={{ background: CREAM2, borderRadius: 4, padding: '6px 12px' }}>
                <div style={{ fontFamily: bodyFont, fontSize: '0.75rem', fontWeight: 700, color: PURPLE, textTransform: 'uppercase', marginBottom: 4 }}>COMMON MISCONCEPTIONS</div>
                <ul style={{ margin: 0, paddingLeft: 14, listStyle: 'disc' }}>
                  {misconceptions.map((mc: string, i: number) => (
                    <li key={i} style={{ fontFamily: bodyFont, fontSize: '0.85rem', color: DARK, marginBottom: 2 }}>{renderBoldPlaceholders(mc)}</li>
                  ))}
                </ul>
              </div>
            )}
            {/* Key takeaway */}
            {slideData.keyTakeaway && (
              <div style={{ background: CREAM2, borderRadius: 4, padding: '6px 12px', borderLeft: `3px solid ${PURPLE}` }}>
                <div style={{ fontFamily: bodyFont, fontSize: '0.75rem', fontWeight: 700, color: PURPLE, textTransform: 'uppercase', marginBottom: 3 }}>KEY TAKEAWAY</div>
                <p style={{ fontFamily: bodyFont, fontSize: '0.9rem', color: DARK, margin: 0, fontStyle: 'italic', lineHeight: 1.4 }}>
                  {slideData.keyTakeaway}
                </p>
              </div>
            )}
          </div>
        </div>
      );
    }

    // ── Break ────────────────────────────────────────────────────────────────
    if (slideData.group === 'break') {
      return (
        <div style={{ background: CREAM, width: '100%', height: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 10 }}>
          <div style={{ fontSize: '3.5rem' }}>☕</div>
          <h2 style={{ fontFamily: titleFont, fontSize: '2rem', fontWeight: 700, color: DARK, margin: 0 }}>Break</h2>
          <p style={{ fontFamily: bodyFont, fontSize: '1.05rem', color: MUTED, margin: 0 }}>
            {slideData.bullets?.[0] || '10 minutes — see you soon!'}
          </p>
        </div>
      );
    }

    // ── Default: content bullets ─────────────────────────────────────────────
    return shell(
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {(slideData.bullets || []).map((bullet, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
            <div style={{ width: 6, height: 6, borderRadius: '50%', background: accent, marginTop: 7, flexShrink: 0 }} />
            <p style={{ fontFamily: bodyFont, fontSize: '1rem', color: DARK, lineHeight: 1.4, margin: 0 }}>
              {renderBoldPlaceholders(typeof bullet === 'string' ? bullet : String(bullet))}
            </p>
          </div>
        ))}
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

        </div>
        <div className="flex items-center gap-2 mr-2">
          {hasSlides && showSlidesGenerated && (
            <div className="hidden lg:flex items-center justify-center gap-1.5 px-2 py-1.5 rounded-md bg-emerald-50 border border-emerald-200 mr-1 transition-opacity duration-500">
              <Check className="h-3 w-3 text-emerald-600" />
              <span className="text-emerald-700" style={{ fontSize: '0.75rem', fontFamily: 'var(--hestia-font-mono)', fontWeight: 500 }}>
                {allCachedSlides.length + 1} slides
              </span>
            </div>
          )}

          <button
            onClick={(e) => {
              e.stopPropagation();
              // Regenerating replaces the slides, so unsaved edits are settled first
              confirmLeave(() => handleGenerate());
            }}
            disabled={isGenerating || isUploading}
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
            {isGenerating ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
            <span style={{ fontSize: '0.75rem', fontWeight: 600 }}>
              {isGenerating ? "Generating…" : hasSlides ? "Regenerate" : "Generate"}
            </span>
          </button>

          {hasSlides && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                // The download uses the saved slides, so unsaved edits are settled first
                confirmLeave(() => handleDownload());
              }}
              disabled={isSaving}
              className="flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-md transition-colors focus:outline-none focus-visible:ring-2 disabled:opacity-50 text-white"
              style={{
                background: 'var(--hestia-primary)',
                border: 'none',
                fontFamily: 'var(--hestia-font-body)'
              }}
            >
              {isSaving ? <RefreshCw className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />}
              <span style={{ fontSize: '0.75rem', fontWeight: 600 }}>
                {isSaving ? "Exporting…" : "Download"}
              </span>
            </button>
          )}

          {hasSlides && (
            <button
              onClick={(e) => {
                e.stopPropagation();
                if (isEditMode) confirmLeave(() => {});
                else handleEditSlide();
                if (!isOpen && !isEditMode) setIsOpen(true);
              }}
              disabled={isGenerating || isUploading || isSavingEdits}
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
              <TemplateUploadBox
                fileName={templateInfo ? (templateInfo.name ?? null) || (templateInfo.source === "COURSE" ? "Course template" : "Saved Template") : null}
                caption={templateInfo?.source === "COURSE" ? "From course template" : "Click to replace"}
                isBusy={isUploading}
                onUpload={handleTemplateUpload}
                onRemove={handleTemplateRemove}
                removeTitle={templateInfo?.source === "COURSE" ? "Use the default design for this session" : "Remove template"}
                note={templateInfo?.source === "COURSE" ? "Removing it here only affects this session." : undefined}
              />
            </div>

            {hasSlides && (
              <>
                <div style={{ height: '1px', background: 'var(--hestia-border)', margin: '12px 0' }} />
                <div className="space-y-3">
                  <h4 style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--hestia-text-muted)' }}>
                    Slide Prep Tasks
                  </h4>
                  {(() => {
                    if (currentSlideIndex === 0) {
                      return <p style={{ fontSize: '0.875rem', color: 'var(--hestia-text-muted)' }}>No specific preparation tasks for this slide.</p>;
                    }

                    const slide = allCachedSlides[currentSlideIndex - 1];
                    if (!slide) return null;

                    const isLecture = slide.layout === 'lecture_placeholder' || slide.layout === 'content' || slide.group === 'activate_prior_knowledge' || slide.group === 'main_lecture';
                    const isActivity = slide.layout?.startsWith('activity_') || slide.layout === 'live_poll' || slide.layout === 'debrief';

                    const tasks = [];

                    if (isActivity) {
                      tasks.push({ id: 'review-instructions', text: 'Review activity instructions and prompt' });

                      if (currentBlockIndex !== -1) {
                        const block = session.blocks[currentBlockIndex];
                        const materials = [...(block.materials || []), ...(block.sections || []).flatMap(s => s.materials || [])]
                          .filter(Boolean)
                          .map(m => String(m).trim())
                          .filter(m => !m.toLowerCase().includes("slide"));

                        const uniqueMaterials = Array.from(new Set(materials));
                        if (uniqueMaterials.length > 0) {
                          tasks.push({ id: 'prepare-material', text: `Prepare material: ${uniqueMaterials.join(', ')}` });
                        }
                      }
                    } else if (isLecture) {
                      tasks.push({ id: 'replace-slide', text: 'Replace slide with your own lecture slide' });
                    }

                    if (tasks.length === 0) {
                      return <p style={{ fontSize: '0.875rem', color: 'var(--hestia-text-muted)' }}>No specific preparation tasks for this slide.</p>;
                    }

                    return (
                      <div className="space-y-4">
                        {tasks.map(task => {
                          const taskId = `slide-${currentSlideIndex}-${task.id}`;
                          const isDone = completedSlideTasks.has(taskId);
                          return (
                            <div key={task.id} className="flex items-start gap-3 cursor-pointer group" onClick={() => toggleSlideTask(taskId)}>
                              <div className={`mt-0.5 shrink-0 flex items-center justify-center w-4 h-4 rounded border transition-colors ${isDone ? 'bg-emerald-500 border-emerald-500 text-white' : 'border-[var(--hestia-border)] bg-transparent group-hover:border-[var(--hestia-primary)]'}`}>
                                {isDone && <Check className="w-3 h-3" />}
                              </div>
                              <span className={`text-[0.875rem] leading-snug transition-colors ${isDone ? 'text-[var(--hestia-text-muted)] line-through' : 'text-[var(--hestia-text)]'}`}>
                                {task.text}
                              </span>
                            </div>
                          );
                        })}
                      </div>
                    );
                  })()}
                </div>
              </>
            )}
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
                            <>
                              <div className="space-y-2 mt-4">
                                <label style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-text-muted)', textTransform: 'uppercase' }}>SUGGESTED ANSWER</label>
                                <textarea
                                  className="w-full h-24 focus:outline-none transition-all resize-y"
                                  style={{ background: 'var(--hestia-surface)', border: '1.5px solid var(--hestia-border)', borderRadius: 'var(--hestia-radius-sm)', padding: '10px 12px', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                                  onFocus={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-primary)'; e.currentTarget.style.boxShadow = '0 0 0 3px var(--hestia-primary-muted)' }}
                                  onBlur={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.boxShadow = 'none' }}
                                  value={slide?.suggestedAnswer || ""}
                                  onChange={e => {
                                    const newCache = { ...editableCache };
                                    newCache[currentBlockIndex][currentSlideWithinBlock].suggestedAnswer = e.target.value;
                                    setEditableCache(newCache);
                                  }}
                                />
                              </div>
                              <div className="space-y-2 mt-4">
                                <label style={{ fontFamily: 'var(--hestia-font-mono)', fontSize: '0.75rem', fontWeight: 600, color: 'var(--hestia-text-muted)', textTransform: 'uppercase' }}>COMMON MISCONCEPTIONS (ONE PER LINE)</label>
                                <textarea
                                  className="w-full h-24 focus:outline-none transition-all resize-y"
                                  style={{ background: 'var(--hestia-surface)', border: '1.5px solid var(--hestia-border)', borderRadius: 'var(--hestia-radius-sm)', padding: '10px 12px', fontFamily: 'var(--hestia-font-body)', fontSize: '0.875rem', color: 'var(--hestia-text)' }}
                                  onFocus={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-primary)'; e.currentTarget.style.boxShadow = '0 0 0 3px var(--hestia-primary-muted)' }}
                                  onBlur={(e) => { e.currentTarget.style.borderColor = 'var(--hestia-border)'; e.currentTarget.style.boxShadow = 'none' }}
                                  value={(slide?.commonMisconceptions || []).join("\n")}
                                  onChange={e => {
                                    const newCache = { ...editableCache };
                                    newCache[currentBlockIndex][currentSlideWithinBlock].commonMisconceptions = e.target.value.split("\n");
                                    setEditableCache(newCache);
                                  }}
                                />
                              </div>
                            </>
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
