import React from "react";
import type { BlockContent, ShownStep } from "@/lib/block-content";

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
export function BlockDetails({ content, methods, isEditMode = false, renderText, className = "pl-4" }: {
  content: BlockContent;
  methods: string[];
  isEditMode?: boolean;
  renderText: (item: ShownStep, className: string) => React.ReactNode;
  className?: string;
}) {
  const activityHeading = `Activity${methods.length > 0 ? ` · ${methods[0]}` : ""}`;
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
          <Heading>Activity Prompt</Heading>
          <div className="px-3 py-2 rounded-lg text-sm italic leading-relaxed" style={promptStyle("var(--hestia-phase-evaluate)")}>
            {renderText(content.prompt, "w-full")}
          </div>
        </div>
      );

    case "cycle":
      return (
        <div className={`${className} space-y-3`}>
          {content.content.length > 0 && (
            <div>
              <Heading>Content to Teach</Heading>
              {list(content.content, i => <span className="shrink-0 mt-0.5 text-muted-foreground font-mono">{i + 1}.</span>)}
            </div>
          )}
          {activityBox(content.activity, "var(--hestia-phase-setup)")}
        </div>
      );

    case "summary":
      return (
        <div className={`${className} space-y-3`}>
          {content.takeaways.length > 0 && (
            <div>
              <Heading>Key Takeaways</Heading>
              {list(content.takeaways, () => <span className="shrink-0 mt-0.5" style={{ color: "var(--hestia-primary)" }}>✦</span>)}
            </div>
          )}
          {activityBox(content.activity, "var(--hestia-primary)")}
        </div>
      );

    default:
      return null;
  }
}
