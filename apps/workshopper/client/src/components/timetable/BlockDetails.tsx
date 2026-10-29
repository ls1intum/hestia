import React from "react";
import type { BlockContent, ShownStep } from "@/lib/block-content";
import type { ActivitySection } from "@/lib/workshop-generator";
import { MinutesInput } from "./MinutesInput";

const itemStyle: React.CSSProperties = {
  backgroundColor: "var(--hestia-surface)",
  border: "1px solid color-mix(in srgb, var(--hestia-text) 10%, transparent)",
};

const promptStyle = (accent: string): React.CSSProperties => ({ ...itemStyle, borderLeft: `2px dashed ${accent}` });

function Heading({ children }: { children: React.ReactNode }) {
  return <p className="text-xs font-mono uppercase tracking-widest text-muted-foreground mb-2">{children}</p>;
}

/**
 * The expanded details of an Arrive, Activate, Learning Cycle or Summary block, shared by the
 * timetable (editable) and Final Review (read-only). Evaluate and other blocks are drawn by the caller.
 *
 * `renderText` draws one item: the timetable makes it editable, Final Review shows the text.
 */
export function BlockDetails({ content, methods, isEditMode = false, renderText, className = "pl-4", sections, onSaveSectionDuration }: {
  content: BlockContent;
  methods: string[];
  isEditMode?: boolean;
  renderText: (item: ShownStep, className: string) => React.ReactNode;
  className?: string;
  /** Raw block sections, used to show per-section time allocations when available */
  sections?: ActivitySection[];
  /** Called when the user edits a section's time badge in edit mode */
  onSaveSectionDuration?: (sectionIdx: number, v: string) => void;
}) {
  const activityHeading = `Participants Do${methods.length > 0 ? ` · ${methods[0]}` : ""}`;
  const activityBox = (item: ShownStep, accent: string) => (item.text || isEditMode) && (
    <div>
      <Heading>{activityHeading}</Heading>
      <div className="px-3 py-2 rounded-lg text-sm italic leading-relaxed flex items-start" style={promptStyle(accent)}>
        {renderText(item, "w-full")}
      </div>
    </div>
  );
  const list = (items: ShownStep[], marker: (i: number) => React.ReactNode) => (
    <div className="space-y-1">
      {items.map((item, i) => (
        <div key={i} className="flex items-start gap-2 px-3 py-1.5 rounded-lg text-sm" style={itemStyle}>
          {marker(i)}
          {renderText(item, "leading-relaxed w-full")}
        </div>
      ))}
    </div>
  );

  switch (content.kind) {
    case "goals":
      return (
        <div className={`${className} space-y-1.5`}>
          <Heading>Learning Goals</Heading>
          {list(content.goals, i => (
            <span className="font-mono text-xs font-bold shrink-0 mt-0.5 whitespace-nowrap" style={{ color: "var(--hestia-primary)" }}>
              Learning Goal {i + 1}
            </span>
          ))}
        </div>
      );

    case "prompt":
      return (
        <div className={`${className} space-y-1.5`}>
          <Heading>Participants Do</Heading>
          <div className="px-3 py-2 rounded-lg text-sm italic leading-relaxed" style={promptStyle("var(--hestia-phase-evaluate)")}>
            {renderText(content.prompt, "w-full")}
          </div>
        </div>
      );

    case "cycle": {
      const explainDuration = sections?.[0]?.duration;
      const activityDuration = sections?.[1]?.duration ?? sections?.find((_, i) => i > 0)?.duration;
      const activitySectionIdx = sections && sections.length > 1 ? 1 : undefined;
      return (
        <div className={`${className} space-y-3`}>
          {content.content.length > 0 && (
            <div>
              <Heading>
                You Explain
                {isEditMode && onSaveSectionDuration && explainDuration != null ? (
                  <span className="ml-2 inline-flex items-center gap-0.5">
                    <MinutesInput
                      value={explainDuration}
                      onSave={(v: string) => onSaveSectionDuration(0, v)}
                      className="w-[34px] px-1 text-right text-xs font-mono"
                      title="Explain section duration (minutes)"
                    />
                    <span className="text-[0.7rem] font-mono opacity-60">m</span>
                  </span>
                ) : explainDuration != null ? (
                  <span className="ml-1 font-mono normal-case tracking-normal text-[0.7rem] opacity-60">· {explainDuration}m</span>
                ) : null}
              </Heading>
              {list(content.content, i => <span className="shrink-0 mt-0.5 text-muted-foreground font-mono">{i + 1}.</span>)}
            </div>
          )}
          {(content.activity.text || isEditMode) && (
            <div>
              <Heading>
                {activityHeading}
                {isEditMode && onSaveSectionDuration && activityDuration != null && activitySectionIdx != null ? (
                  <span className="ml-2 inline-flex items-center gap-0.5">
                    <MinutesInput
                      value={activityDuration}
                      onSave={(v: string) => onSaveSectionDuration(activitySectionIdx, v)}
                      className="w-[34px] px-1 text-right text-xs font-mono"
                      title="Activity section duration (minutes)"
                    />
                    <span className="text-[0.7rem] font-mono opacity-60">m</span>
                  </span>
                ) : activityDuration != null ? (
                  <span className="ml-1 font-mono normal-case tracking-normal text-[0.7rem] opacity-60">· {activityDuration}m</span>
                ) : null}
              </Heading>
              <div className="px-3 py-2 rounded-lg text-sm italic leading-relaxed flex items-start" style={promptStyle("var(--hestia-phase-setup)")}>
                {renderText(content.activity, "w-full")}
              </div>
            </div>
          )}
        </div>
      );
    }

    case "summary":
      return (
        <div className={`${className} space-y-3`}>
          {content.takeaways.length > 0 && (
            <div>
              <Heading>Key Takeaways</Heading>
              {list(content.takeaways, () => <span className="shrink-0 mt-0.5" style={{ color: "var(--hestia-primary)" }}>✦</span>)}
            </div>
          )}
        </div>
      );

    default:
      return null;
  }
}
