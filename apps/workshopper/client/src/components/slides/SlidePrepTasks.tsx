import { Check } from "lucide-react";
import type { ActivityBlock, SlideData } from "@/lib/workshop-generator";

interface PrepTask {
  id: string;
  text: string;
}

/** What the lecturer has to prepare for a slide: activity slides need their material, lecture slides their own content. */
function prepTasks(slide: SlideData, block: ActivityBlock | undefined): PrepTask[] {
  const isLecture = slide.layout === "lecture_placeholder" || slide.layout === "content"
    || slide.group === "activate_prior_knowledge" || slide.group === "main_lecture";
  const isActivity = slide.layout?.startsWith("activity_") || slide.layout === "live_poll" || slide.layout === "debrief";

  if (isActivity) {
    const tasks = [{ id: "review-instructions", text: "Review activity instructions and prompt" }];
    const materials = Array.from(new Set(
      [...(block?.materials || []), ...(block?.sections || []).flatMap(s => s.materials || [])]
        .filter(Boolean)
        .map(m => String(m).trim())
        .filter(m => !m.toLowerCase().includes("slide")),
    ));
    if (materials.length > 0) tasks.push({ id: "prepare-material", text: `Prepare material: ${materials.join(", ")}` });
    return tasks;
  }
  return isLecture ? [{ id: "replace-slide", text: "Replace slide with your own lecture slide" }] : [];
}

/**
 * The prep checklist of the current slide. A ticked task is stored as `slide-<index>-<task id>`
 * (saved with the session as DraftState.completedTasks).
 */
export function SlidePrepTasks({ slide, slideIndex, block, completedTasks, onToggle }: {
  /** Undefined for the title slide */
  slide: SlideData | undefined;
  slideIndex: number;
  block: ActivityBlock | undefined;
  completedTasks: string[];
  onToggle: (taskId: string) => void;
}) {
  const tasks = slide ? prepTasks(slide, block) : [];
  if (tasks.length === 0) {
    return <p style={{ fontSize: "0.875rem", color: "var(--hestia-text-muted)" }}>No specific preparation tasks for this slide.</p>;
  }

  return (
    <div className="space-y-4">
      {tasks.map(task => {
        const taskId = `slide-${slideIndex}-${task.id}`;
        const isDone = completedTasks.includes(taskId);
        return (
          <div key={task.id} className="flex items-start gap-3 cursor-pointer group" onClick={() => onToggle(taskId)}>
            <div className={`mt-0.5 shrink-0 flex items-center justify-center w-4 h-4 rounded border transition-colors ${isDone ? "bg-emerald-500 border-emerald-500 text-white" : "border-[var(--hestia-border)] bg-transparent group-hover:border-[var(--hestia-primary)]"}`}>
              {isDone && <Check className="w-3 h-3" />}
            </div>
            <span className={`text-[0.875rem] leading-snug transition-colors ${isDone ? "text-[var(--hestia-text-muted)] line-through" : "text-[var(--hestia-text)]"}`}>
              {task.text}
            </span>
          </div>
        );
      })}
    </div>
  );
}
