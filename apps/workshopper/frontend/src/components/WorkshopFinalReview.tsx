import React, { useState } from "react";
import { saveAs } from "file-saver";
import { ActivityBlock, LearningGoalPlan, WorkshopSession, WorkshopInput, SlideData } from "@/lib/workshop-generator";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Clock, Target, ArrowLeft, Download, CheckCircle2, ChevronDown, Check, X, AlertTriangle, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { toast } from "@/hooks/use-toast";

const phaseColors: Record<string, string> = {
  ARRIVE: "bg-primary/10 text-primary border-primary/20",
  ACTIVATE: "bg-accent/10 text-accent border-accent/20",
  INFORM: "bg-primary/10 text-primary border-primary/20",
  PROCESS: "bg-accent/10 text-accent border-accent/20",
  BREAK: "bg-muted text-muted-foreground border-border",
  EVALUATE: "bg-primary/10 text-primary border-primary/20",
  SUMMARY: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400 border-emerald-400/30",
};

const phaseEmojis: Record<string, string> = {
  ARRIVE: "👋",
  ACTIVATE: "💡",
  INFORM: "🗣️",
  PROCESS: "🛠️",
  BREAK: "☕",
  EVALUATE: "✅",
  SUMMARY: "🏁",
  LEARNING_CYCLE: "🎯",
};

const phaseRowColors: Record<string, string> = {
  ARRIVE: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-primary",
  ACTIVATE: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-accent",
  INFORM: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-primary/80",
  PROCESS: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-accent/80",
  BREAK: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-muted-foreground",
  EVALUATE: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-primary/60",
  SUMMARY: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-accent/60",
  LEARNING_CYCLE: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-primary",
  CUSTOM: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-border",
  BUFFER: "bg-muted/30 relative before:absolute before:inset-y-0 before:left-0 before:w-[3px] before:bg-muted",
};

const getStepEmoji = (text: string) => {
  const t = text.toLowerCase();
  if (t.includes("lecture") || t.includes("presentation") || t.includes("explain") || t.includes("concept")) return "🧑‍🏫";
  if (t.includes("prompt") || t.includes("question") || t.includes("q&a") || t.includes("quiz")) return "❓";
  if (t.includes("activity") || t.includes("exercise") || t.includes("practice")) return "🛠️";
  if (t.includes("discuss") || t.includes("debate") || t.includes("share")) return "💬";
  if (t.includes("brainstorm") || t.includes("ideate")) return "💡";
  if (t.includes("review") || t.includes("feedback") || t.includes("evaluate")) return "✅";
  if (t.includes("welcome") || t.includes("intro")) return "👋";
  if (t.includes("wrap") || t.includes("summary") || t.includes("conclusion")) return "🏁";
  if (t.includes("break") || t.includes("pause")) return "☕";
  if (t.includes("read") || t.includes("case study")) return "📖";
  if (t.includes("video") || t.includes("watch")) return "🎥";
  if (t.includes("role play") || t.includes("simulate")) return "🎭";
  if (t.includes("icebreaker") || t.includes("game")) return "🎲";
  return "✨";
};

const getStepColorClass = (text: string) => {
  return "bg-card border-border/60";
};

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

const DEFAULT_ACTIVITIES = [
  "Group Discussion", "Case Study", "Role Play",
  "Hands-on Practice", "Quiz / Polls", "Q&A Session",
  "Peer Review", "Brainstorming", "Think-Pair-Share"
];

interface Props {
  session: WorkshopSession;
  goals?: LearningGoalPlan[];
  meta: WorkshopInput;
  slidesCache: Record<number, SlideData[]>;
  onBack: () => void;
  onDone: () => void;
}

const fmtTime = (m: number) => {
  const h = Math.floor(m / 60);
  const mm = (m % 60).toString().padStart(2, "0");
  return `${h.toString().padStart(2, "0")}:${mm}`;
};

export default function WorkshopFinalReview({ session, goals = [], meta, slidesCache, onBack, onDone }: Props) {
  const [isExporting, setIsExporting] = useState(false);

  const totalDuration = session.blocks.reduce((s, b) => s + (b.duration || 0), 0);

  // Compute running start times
  let cursor = 0;
  const rows = session.blocks.map((block) => {
    const start = cursor;
    cursor += block.duration;
    return { block, start: fmtTime(start), end: fmtTime(cursor) };
  });

  const downloadDeck = async () => {
    setIsExporting(true);
    toast({ title: "Preparing Deck…", description: "Combining cached slides and generating any missing ones." });
    try {
      const allCachedSlides = session.blocks.flatMap((_, i) => slidesCache[i] ?? []);
      const hasAllCached = session.blocks.every((_, i) => !!slidesCache[i]);

      let blob: Blob;
      if (hasAllCached && allCachedSlides.length > 0) {
        const res = await fetch(import.meta.env.BASE_URL + "api/workshop/export/pptx-assemble", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ session, meta, prebuiltSlides: allCachedSlides }),
        });
        if (!res.ok) throw new Error("Assembly failed");
        blob = await res.blob();
      } else {
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
    <div className="space-y-6 pb-20">
      <Card className="border-border/60 shadow-lg bg-white dark:bg-zinc-900 flex flex-col max-w-5xl mx-auto w-full">
        {/* Header Block */}
        <div className="p-6 pb-2">
          <div className="font-display text-2xl mb-2 font-semibold flex items-center justify-between gap-4">
            <span>{session.title || "Workshop Session Plan"}</span>
          </div>
          <div className="flex items-center gap-x-6 gap-y-2 flex-wrap text-sm text-muted-foreground font-body">
            <span className="flex items-center gap-1.5 font-medium"><Clock className="h-4 w-4 text-primary/60" /> {meta.duration || totalDuration} min</span>
            <span className="flex items-center gap-1.5 font-medium"><Users className="h-4 w-4 text-primary/60" /> {meta.participants} participants{session.studentBackground ? `, ${session.studentBackground}` : ""}</span>
          </div>

          <div className="mt-5 space-y-4">
            <div>
              <h3 className="font-body font-semibold flex items-center gap-2 mb-1"><Target className="h-4 w-4 text-primary" /> Learning Goals</h3>
              <ul className="text-sm font-body text-foreground/80 flex flex-col gap-1.5 list-none">
                {goals && goals.length > 0 ? goals.map((g, idx) => (
                  <li key={idx} className="flex items-start gap-1.5">
                    <span className="text-primary/60 mt-0.5">•</span>
                    <span className="leading-snug">{g.goal}</span>
                  </li>
                )) : (
                  <li className="flex items-start gap-1.5">
                    <span className="text-primary/60 mt-0.5">•</span>
                    <span className="leading-snug">{session.learningGoal}</span>
                  </li>
                )}
              </ul>
            </div>

            {session.prerequisites && (
              <div>
                <h3 className="font-body font-semibold flex items-center gap-2 mb-1"><Target className="h-4 w-4 text-primary" /> Prerequisites</h3>
                <ul className="text-sm font-body text-foreground/80 flex flex-wrap gap-x-4 gap-y-1.5 list-none">
                  {session.prerequisites?.split(';').filter(p => p.trim() !== '').map((prereq, idx) => (
                    <li key={idx} className="flex items-start gap-1.5 max-w-full">
                      <span className="text-primary/60 mt-0.5">•</span>
                      <span className="leading-snug text-muted-foreground">{prereq.trim()}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        </div>

        {/* Timetable */}
        <div className="flex-1 px-2 sm:px-12 py-6 pb-8">
          <div className="mx-auto max-w-3xl">

                <div className="relative mt-2">
                  {/* Main Timeline track */}
                  <div className="absolute top-8 bottom-8 left-[64px] w-[2px] bg-border/60 z-0 hidden sm:block" />
                  <Accordion type="multiple" className="space-y-4">
                    {rows.map(({ block, start, end }, i) => {
                      const innerContent = (
                          <div className="relative flex items-start sm:items-center gap-4 w-full text-left">
                            
                            {/* Time block */}
                            <div className="w-12 shrink-0 flex flex-col justify-center mt-2 sm:mt-0 relative z-10 text-right hidden sm:flex">
                              <span className="text-xs font-bold text-foreground/80 py-1 pr-2">{block.duration}m</span>
                            </div>

                            {/* Main Timeline dot */}
                            <div className="absolute left-[60px] top-1/2 -translate-y-1/2 w-2.5 h-2.5 rounded-full bg-card border-[2.5px] border-primary/50 shadow-sm z-10 ring-4 ring-background group-hover:border-primary transition-colors hidden sm:block" />

                            {/* Content box */}
                            <div className={`flex-1 border border-border/60 group-hover:border-primary/40 transition-all rounded-xl p-3 sm:py-4 shadow-sm flex items-center gap-3 overflow-hidden min-w-0 ${phaseRowColors[block.phase] || 'bg-white dark:bg-zinc-900'}`}>
                              <div className="flex items-center gap-3 overflow-hidden min-w-0 flex-1">
                                <div className="sm:hidden flex flex-col items-start shrink-0 mr-2 border-r border-border/50 pr-3">
                                  <span className="text-xs font-bold text-foreground/80">{block.duration}m</span>
                                </div>
                                <span className="font-body text-sm font-medium text-foreground whitespace-nowrap shrink-0 flex items-center gap-1.5">
                                  <span className="text-base">{phaseEmojis[block.phase] || "✨"}</span>
                                  {block.phaseLabel}
                                </span>
                            {(() => {
                              const allMethods = Array.from(new Set([
                                ...(block.methods || []),
                                ...(block.sections || []).flatMap(s => s.methods || [])
                              ])).filter(m => m && !m.toLowerCase().includes("lecture") && !m.toLowerCase().includes("presentation"));

                              if (allMethods.length === 0) return null;

                              return (
                                  <div className="flex items-center gap-1.5 overflow-x-auto no-scrollbar">
                                    <div className="h-3 w-[1px] bg-border/60 mx-1 shrink-0"></div>
                                    {allMethods.map((m, j) => (
                                      <TooltipProvider key={`meth-${j}`}>
                                        <Tooltip>
                                          <TooltipTrigger asChild>
                                            <div className="inline-flex items-center justify-center whitespace-nowrap rounded-md border h-6 px-2 text-[10px] font-medium bg-background/50 text-foreground/80 border-border/50 shrink-0 backdrop-blur-sm cursor-help">
                                              {m}
                                            </div>
                                          </TooltipTrigger>
                                          <TooltipContent side="top" className="max-w-[250px] text-xs font-body font-normal">
                                            {getMechanicDescription(m)}
                                          </TooltipContent>
                                        </Tooltip>
                                      </TooltipProvider>
                                    ))}
                                  </div>
                              );
                            })()}
                              </div>
                              {block.phase !== "BREAK" && <ChevronDown className="h-4 w-4 text-muted-foreground transition-transform duration-200 shrink-0 ml-auto" />}
                            </div>
                          </div>
                      );

                      if (block.phase === "BREAK") {
                        return (
                          <div key={block.blockId || i} className="border-0 relative bg-transparent px-4 py-1 group">
                            {innerContent}
                          </div>
                        );
                      }

                      return (
                        <AccordionItem key={block.blockId || i} value={`item-${block.blockId || i}`} className={`border-0 relative bg-transparent`}>
                          <AccordionTrigger className="px-4 py-1 hover:no-underline [&>svg]:hidden group">
                            {innerContent}
                          </AccordionTrigger>
                          <AccordionContent className="pl-[20px] sm:pl-[100px] pr-4 pb-4 pt-2">
                            {block.sections && block.sections.length > 0 && (
                              <div className="relative mt-2">
                                {/* Sub Timeline track */}
                                <div className="absolute top-3 bottom-3 left-[43px] w-[2px] bg-border/40" />

                                <div className="space-y-3">
                                    {block.sections.flatMap(s => s.steps || []).map((step, stepIdx) => {
                                      // Parse out duration like "2 min — "
                                      const match = step.match(/^(\d+)\s*(?:min|m)(?:utes?)?\s*(?:—|-|:)\s*(.*)/i);
                                      const timeVal = match ? match[1] : "";
                                      let contentText = match ? match[2] : step;

                                      let subEmoji = getStepEmoji(contentText);
                                      let subColorClass = getStepColorClass(contentText);

                                      return (
                                        <div key={stepIdx} className="relative flex items-start gap-4 group">
                                          {/* Time block */}
                                          <div className="w-9 shrink-0 flex justify-end mt-1 relative z-10">
                                            {timeVal ? (
                                              <span className="text-[11px] font-bold text-primary/80 bg-primary/10 px-1.5 py-0.5 rounded shadow-sm">{timeVal}m</span>
                                            ) : null}
                                          </div>

                                          {/* Timeline dot */}
                                          <div className="absolute left-[39px] top-2.5 w-2.5 h-2.5 rounded-full bg-card border-[2.5px] border-primary/50 shadow-sm z-10 ring-2 ring-background group-hover:border-primary transition-colors" />

                                          {/* Content box */}
                                          <div className={`border ${subColorClass} group-hover:border-primary/40 group-hover:shadow-md transition-all rounded-lg px-3 py-2.5 text-sm font-body text-foreground/90 leading-relaxed shadow-sm flex-1`}>
                                            {subEmoji && <span className="mr-1.5">{subEmoji}</span>}
                                            {contentText}
                                          </div>
                                        </div>
                                      );
                                    })}
                                  </div>
                                </div>
                              )}
                        </AccordionContent>
                        </AccordionItem>
                      );
                    })}
                  </Accordion>
                </div>
          </div>
        </div>

        {/* Omitted goals warning */}
        {session.omittedGoals && session.omittedGoals.length > 0 && (
          <div className="m-4 rounded-lg border border-amber-400/60 bg-amber-50/60 dark:bg-amber-950/30 dark:border-amber-500/40 px-4 py-3 space-y-2">
            <div className="flex items-start gap-2">
              <AlertTriangle className="h-4 w-4 text-amber-500 mt-0.5 shrink-0" />
              <div>
                <p className="text-sm font-body font-semibold text-amber-800 dark:text-amber-300">
                  Not all learning goals could be covered
                </p>
                <p className="text-xs font-body text-amber-700 dark:text-amber-400 mt-0.5">
                  The following goal{session.omittedGoals.length > 1 ? "s were" : " was"} left out because there was not enough time to cover {session.omittedGoals.length > 1 ? "them" : "it"} meaningfully within the session duration. Consider reducing other goals or increasing the session time.
                </p>
              </div>
            </div>
            <ul className="ml-6 space-y-1">
              {session.omittedGoals.map((g, i) => (
                <li key={i} className="text-xs font-body text-amber-800 dark:text-amber-300 flex items-start gap-1.5">
                  <span className="text-amber-500 mt-0.5">•</span>
                  {g}
                </li>
              ))}
            </ul>
          </div>
        )}
        {/* Sticky Footer Bar */}
        <div className="sticky bottom-4 z-30 mx-4 mb-4 rounded-xl border border-border/80 bg-white/95 dark:bg-zinc-900/95 backdrop-blur-md shadow-2xl p-3 flex items-center justify-between gap-4 mt-6 transition-all">
          <Button variant="outline" size="sm" onClick={onBack} className="gap-2 font-body shrink-0">
            <ArrowLeft className="h-4 w-4" /> Previous
          </Button>

          <div className="flex items-center gap-2 flex-wrap justify-end">
            <Button size="sm" variant="outline" onClick={downloadPdf} disabled={isExporting} className="gap-2">
              <Download className="h-3.5 w-3.5" /> PDF Timetable
            </Button>
            <Button size="sm" variant="outline" onClick={downloadDeck} disabled={isExporting} className="gap-2">
              <Download className="h-3.5 w-3.5" /> PPTX Slides
            </Button>
            <Button size="sm" variant="default" onClick={onDone} className="gap-2 shadow-md hover:shadow-lg transition-shadow bg-emerald-600 hover:bg-emerald-700 text-white">
              <CheckCircle2 className="h-3.5 w-3.5" /> Finish & Save
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}
