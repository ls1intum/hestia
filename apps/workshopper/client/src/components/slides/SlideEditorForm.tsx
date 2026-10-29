import React from "react";
import type { SlideData } from "@/lib/workshop-generator";
import { coerceToString } from "./SlidePreview";

const fieldStyle: React.CSSProperties = {
  background: "var(--hestia-surface)",
  border: "1.5px solid var(--hestia-border)",
  borderRadius: "var(--hestia-radius-sm)",
  padding: "10px 12px",
  fontFamily: "var(--hestia-font-body)",
  fontSize: "0.875rem",
  color: "var(--hestia-text)",
};

const focusHandlers = {
  onFocus: (e: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    e.currentTarget.style.borderColor = "var(--hestia-primary)";
    e.currentTarget.style.boxShadow = "0 0 0 3px var(--hestia-primary-muted)";
  },
  onBlur: (e: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    e.currentTarget.style.borderColor = "var(--hestia-border)";
    e.currentTarget.style.boxShadow = "none";
  },
};

/** One labelled input; with `height` (a Tailwind class such as "h-24") it is a textarea. */
function Field({ label, value, onChange, height, className = "", labelColor = "var(--hestia-text-muted)" }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  height?: string;
  className?: string;
  labelColor?: string;
}) {
  return (
    <div className={`space-y-2 ${className}`}>
      <label style={{ fontFamily: "var(--hestia-font-mono)", fontSize: "0.75rem", fontWeight: 600, color: labelColor, textTransform: "uppercase" }}>
        {label}
      </label>
      {height ? (
        <textarea className={`w-full ${height} focus:outline-none transition-all resize-y`} style={fieldStyle} {...focusHandlers}
          value={value} onChange={e => onChange(e.target.value)} />
      ) : (
        <input className="w-full focus:outline-none transition-all" style={fieldStyle} {...focusHandlers}
          value={value} onChange={e => onChange(e.target.value)} />
      )}
    </div>
  );
}

const lines = (value: string[] | undefined) => (value || []).join("\n");
const toLines = (value: string) => value.split("\n");

/**
 * The fields of one slide in the slide editor. Which fields are shown depends on the slide's layout;
 * every change is reported as a patch of the slide.
 */
export function SlideEditorForm({ slide, onChange }: {
  slide: SlideData | undefined;
  onChange: (patch: Partial<SlideData>) => void;
}) {
  const layout = slide?.layout || "default";
  const isActivity = layout.startsWith("activity_");

  return (
    <div className="flex-1 p-8 overflow-y-auto space-y-6">
      <Field label="Title" value={slide?.title || ""} onChange={title => onChange({ title })} />
      <Field label="Subtitle" value={slide?.subtitle || ""} onChange={subtitle => onChange({ subtitle })} />

      {isActivity && (
        <>
          <Field label="Activity prompt" height="h-24" value={slide?.activityPrompt || ""}
            onChange={activityPrompt => onChange({ activityPrompt })} />
          <Field label="Instructions (one per line)" height="h-24" value={lines(slide?.activityInstructions)}
            onChange={v => onChange({ activityInstructions: toLines(v) })} />
          <Field label="Output expectation" value={slide?.activityOutputExpectation || ""}
            onChange={activityOutputExpectation => onChange({ activityOutputExpectation })} />
        </>
      )}

      {layout === "live_poll" && (
        <>
          <Field label="Poll question" height="h-20" value={slide?.pollQuestion || ""}
            onChange={pollQuestion => onChange({ pollQuestion })} />
          <Field label="Options (one per line)" height="h-24" value={lines(slide?.pollOptions)}
            onChange={v => onChange({ pollOptions: toLines(v) })} />
        </>
      )}

      {layout === "debrief" && (
        <>
          <Field label="Suggested answer" height="h-24" className="mt-4" value={slide?.suggestedAnswer || ""}
            onChange={suggestedAnswer => onChange({ suggestedAnswer })} />
          <Field label="Common misconceptions (one per line)" height="h-24" className="mt-4" value={lines(slide?.commonMisconceptions)}
            onChange={v => onChange({ commonMisconceptions: toLines(v) })} />
        </>
      )}

      {!isActivity && layout !== "live_poll" && layout !== "debrief" && (
        <Field label="Content (bullets)" height="h-32" value={lines(slide?.bullets)}
          onChange={v => onChange({ bullets: toLines(v) })} />
      )}

      <div className="mt-4 pt-4" style={{ borderTop: "1px dashed var(--hestia-border)" }}>
        <Field label="Speaker notes" height="h-24" labelColor="var(--hestia-primary)" value={coerceToString(slide?.notes)}
          onChange={notes => onChange({ notes })} />
      </div>
    </div>
  );
}
