import { useState } from "react";
import { saveAs } from "file-saver";
import { toast } from "@/hooks/use-toast";
import { phaseInfo } from "@/lib/phases";
import {
  deleteSessionTemplate, uploadSessionTemplate, generateBlockSlides, saveSlides, exportPptxWithTemplate,
  errorMessage, handleAuthError, downloadName,
} from "@/lib/api";
import type { LearningGoalPlan, SlideData, TemplateInfo, WorkshopInput, WorkshopSession } from "@/lib/workshop-generator";

/** A slide of the deck, with where it is stored in the per-block slide cache. */
export interface DeckSlide {
  blockIndex: number;
  /** Position within its block's slides */
  slideIndex: number;
  slide: SlideData;
}

/**
 * The deck in order, after the title slide. A fixed instruction slide (e.g. how Think-Pair-Share
 * works) appears only the first time its activity is used.
 */
export function deckSlides(cache: Record<number, SlideData[]>, blockCount: number): DeckSlide[] {
  const seen = new Set<string>();
  const deck: DeckSlide[] = [];
  for (let blockIndex = 0; blockIndex < blockCount; blockIndex++) {
    (cache[blockIndex] || []).forEach((slide, slideIndex) => {
      if (slide.fixedInstructionFor) {
        if (seen.has(slide.fixedInstructionFor)) return;
        seen.add(slide.fixedInstructionFor);
      }
      deck.push({ blockIndex, slideIndex, slide });
    });
  }
  return deck;
}

/** A block's id; blocks saved before every block had one are named by position, like the timetable does. */
const blockKey = (block: { blockId?: string }, index: number) => block.blockId || `block-${index}`;

/**
 * Slides are stored per block position. After the timetable's blocks were moved, deleted or added,
 * returns the slides re-sorted to the new positions (blocks without slides get none), or null if
 * nothing moved.
 */
export function remapSlidesToBlocks(
  before: { blockId?: string }[],
  after: { blockId?: string }[],
  cache: Record<number, SlideData[]>,
): Record<number, SlideData[]> | null {
  if (Object.keys(cache).length === 0) return null;
  const oldIndex = new Map<string, number>();
  before.forEach((b, i) => { if (!oldIndex.has(blockKey(b, i))) oldIndex.set(blockKey(b, i), i); });
  const next: Record<number, SlideData[]> = {};
  let moved = before.length !== after.length;
  after.forEach((b, j) => {
    const i = oldIndex.get(blockKey(b, j));
    if (i !== j) moved = true;
    if (i !== undefined && cache[i]) next[j] = cache[i];
  });
  return moved ? next : null;
}

const GENERATION_CONCURRENCY = 3;

/**
 * Generating, downloading and branding the slide deck of a session (Preparation step).
 * The slides themselves live in the wizard state (`slidesCache`, keyed by block index).
 */
export function useSlideDeck({ session, meta, goals, setSlidesCache, setTemplateInfo }: {
  session: WorkshopSession;
  meta: WorkshopInput;
  goals: LearningGoalPlan[];
  setSlidesCache: (cache: Record<number, SlideData[]>) => void;
  setTemplateInfo?: (v: TemplateInfo | null) => void;
}) {
  const [isGenerating, setIsGenerating] = useState(false);
  const [isDownloading, setIsDownloading] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  /** True for a few seconds after generation, to show the slide count */
  const [justGenerated, setJustGenerated] = useState(false);

  /** Generates the slides of every block (a few blocks at a time) and saves them with the session. */
  const generate = async () => {
    setIsGenerating(true);
    toast({ title: "Generating Lecture Slides", description: "This may take a minute…" });
    const cache: Record<number, SlideData[]> = {};
    try {
      for (let i = 0; i < session.blocks.length; i += GENERATION_CONCURRENCY) {
        const chunk = session.blocks.slice(i, i + GENERATION_CONCURRENCY);
        const results = await Promise.all(chunk.map(async (block, j) => {
          if (!phaseInfo(block.phase).hasSlides) return { idx: i + j, slides: [] };
          try {
            return { idx: i + j, slides: await generateBlockSlides(block, meta, goals) };
          } catch (e) {
            throw handleAuthError(e) ? e : new Error("Failed to generate slides for block " + block.phaseLabel);
          }
        }));
        for (const { idx, slides } of results) cache[idx] = slides;
      }
      setSlidesCache(cache);
      if (session.id) await saveSlides(session.id, cache);

      toast({ title: "Slides Generated!", description: "Scroll right to preview your deck." });
      setJustGenerated(true);
      setTimeout(() => setJustGenerated(false), 4000);
    } catch (e) {
      if (!handleAuthError(e)) toast({ title: "Generation failed", description: errorMessage(e, "Unknown error"), variant: "destructive" });
    } finally {
      setIsGenerating(false);
    }
  };

  /** Downloads the deck as PPTX, in the template stored for the session (or its course). */
  const download = async (slides: SlideData[]) => {
    setIsDownloading(true);
    toast({ title: "Preparing Download…" });
    try {
      const blob = await exportPptxWithTemplate(session, meta, slides);
      saveAs(blob, downloadName.slides(session.title));
      toast({ title: "Downloaded!", description: "Your slide deck is ready." });
    } catch (e) {
      if (!handleAuthError(e)) toast({ title: "Download Failed", description: errorMessage(e, "Assembly failed"), variant: "destructive" });
    } finally {
      setIsDownloading(false);
    }
  };

  const uploadTemplate = async (file: File) => {
    if (!session.id) return;
    setIsUploading(true);
    try {
      await uploadSessionTemplate(session.id, file);
      setTemplateInfo?.({ source: "SESSION", name: file.name });
      toast({ title: "Template Saved", description: "Your branding template has been uploaded." });
    } catch (e) {
      if (!handleAuthError(e)) toast({ title: "Upload Failed", description: errorMessage(e, "Error"), variant: "destructive" });
    } finally {
      setIsUploading(false);
    }
  };

  /** Removes the template from this session only; a course template stays for the course's other sessions. */
  const removeTemplate = async () => {
    if (!session.id) return;
    setIsUploading(true);
    try {
      await deleteSessionTemplate(session.id);
      setTemplateInfo?.(null);
      toast({ title: "Template removed", description: "This session now uses the default design." });
    } catch (e) {
      if (!handleAuthError(e)) toast({ title: "Failed to remove template", variant: "destructive" });
    } finally {
      setIsUploading(false);
    }
  };

  return { isGenerating, isDownloading, isUploading, justGenerated, generate, download, uploadTemplate, removeTemplate };
}
