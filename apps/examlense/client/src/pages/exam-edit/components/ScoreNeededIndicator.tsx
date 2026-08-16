import { useLayoutEffect, useRef, useState } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import { cn } from "@/lib/utils/utils";
import { WayfindingPill } from "@/components/shared/WayfindingPill";

interface Props {
  /** The scrolling content container the target lives inside. */
  scrollRef: React.RefObject<HTMLElement>;
  /** First task in the current section still missing a score, or null. */
  targetTaskId: string | null;
  /** Expand + scroll to + focus the target task's score input. */
  onGoToScore: (taskId: string) => void;
}

type Placement = "above" | "below" | "hidden";

const PAD = 8;

/**
 * Wayfinding helper for the exam editor. When the next task whose score isn't
 * set yet is scrolled out of view, it points the author toward it: pinned to
 * the top (arrow up) when that task is above the viewport, or the bottom
 * (arrow down) when it's below. While the task is in view it stays hidden — the
 * danger-bordered score field is cue enough. It advances to the next unscored
 * task automatically and disappears once the section is ready to confirm.
 */
export const ScoreNeededIndicator = ({
  scrollRef,
  targetTaskId,
  onGoToScore,
}: Props) => {
  const [placement, setPlacement] = useState<Placement>("hidden");
  const rafRef = useRef<number | null>(null);

  useLayoutEffect(() => {
    if (!targetTaskId) return;
    const container = scrollRef.current;
    if (!container) return;

    const measure = () => {
      const target = document.getElementById(`task-${targetTaskId}`);
      if (!target) {
        setPlacement("hidden");
        return;
      }
      const cr = container.getBoundingClientRect();
      const tr = target.getBoundingClientRect();
      if (tr.bottom < cr.top + PAD) setPlacement("above");
      else if (tr.top > cr.bottom - PAD) setPlacement("below");
      else setPlacement("hidden");
    };

    const schedule = () => {
      if (rafRef.current != null) return;
      rafRef.current = requestAnimationFrame(() => {
        rafRef.current = null;
        measure();
      });
    };

    measure();
    container.addEventListener("scroll", schedule, { passive: true });
    window.addEventListener("resize", schedule);
    const ro = new ResizeObserver(schedule);
    ro.observe(container);
    if (container.firstElementChild) ro.observe(container.firstElementChild);

    return () => {
      container.removeEventListener("scroll", schedule);
      window.removeEventListener("resize", schedule);
      ro.disconnect();
      if (rafRef.current != null) cancelAnimationFrame(rafRef.current);
      rafRef.current = null;
    };
  }, [targetTaskId, scrollRef]);

  if (!targetTaskId || placement === "hidden") return null;

  const Icon = placement === "above" ? ChevronUp : ChevronDown;

  return (
    <div
      className={cn(
        "pointer-events-none absolute inset-x-0 z-20 flex justify-center",
        placement === "below" ? "bottom-hestia-4" : "top-hestia-4",
      )}
    >
      <WayfindingPill
        tone="warning"
        label="Score needs to be set"
        icon={<Icon size={14} className="shrink-0" />}
        onClick={() => onGoToScore(targetTaskId)}
        className="pointer-events-auto animate-bounce py-2 shadow-hestia-lg"
      />
    </div>
  );
};
