import React from "react";
import type { SlideData } from "@/lib/workshop-generator";

/*
 * HTML previews of the generated slides, drawn to look like the default PowerPoint design.
 * The real deck is built on the server (AssemblePptxUseCase); this only previews it.
 */

/**
 * Coerce any value the server might return in a string field to an actual string.
 * The LLM occasionally returns notes/bullets as objects or arrays instead of strings.
 */
export const coerceToString = (value: unknown): string => {
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

export const renderBoldPlaceholders = (text: unknown) => {
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

/** The title slide (first slide of the deck). */
export function TitleSlidePreview({ title }: { title?: string }) {
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
        {title || 'Workshop Session'}
      </h1>
      <div style={{ height: 3, width: 48, background: GREEN, marginLeft: 8 }} />
    </div>
  );
}

/** One generated slide, laid out by its `layout` and `group`. */
export function SlidePreview({ slide: slideData }: { slide: SlideData }) {
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
}
