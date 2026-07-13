---
name: HESTIA
description: Warm, considered design system for exam authoring, AI-assisted grading, and results analysis.
colors:
  bg: "#e9e5db"
  surface: "#ffffff"
  primary: "#865c1d"
  primary-hover: "#6b4917"
  primary-muted: "#ebd4b1"
  text: "#2c2420"
  text-muted: "#2c2420a6"
  accent: "#386570"
  warning: "#d4880a"
  danger: "#c0281f"
  success: "#297a52"
  border: "#2c242026"
typography:
  display:
    fontFamily: "Roboto, Georgia, 'Times New Roman', serif"
    fontWeight: 700
    lineHeight: 1.15
    letterSpacing: "-0.01em"
  headline:
    fontFamily: "Roboto, Georgia, 'Times New Roman', serif"
    fontSize: "clamp(2rem, 4vw, 3.5rem)"
    fontWeight: 700
    lineHeight: 1.1
  title:
    fontFamily: "Inter, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 600
    lineHeight: 1.4
  body:
    fontFamily: "Inter, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.5
    fontFeature: "'ss01', 'cv11'"
  label:
    fontFamily: "Inter, system-ui, sans-serif"
    fontSize: "0.625rem"
    fontWeight: 500
    letterSpacing: "0.05em"
rounded:
  sm: "0.375rem"
  md: "0.5rem"
  lg: "0.75rem"
  xl: "1rem"
  full: "9999px"
spacing:
  "1": "0.25rem"
  "2": "0.5rem"
  "3": "0.75rem"
  "4": "1rem"
  "5": "1.5rem"
  "6": "2rem"
  "8": "3rem"
  "10": "4rem"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "#ffffff"
    rounded: "{rounded.md}"
    padding: "0.75rem 1.5rem"
    typography: "{typography.body}"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
  button-success:
    backgroundColor: "{colors.success}"
    textColor: "#ffffff"
    rounded: "{rounded.md}"
    padding: "0.5rem 0.75rem"
  button-danger:
    backgroundColor: "{colors.danger}"
    textColor: "#ffffff"
    rounded: "{rounded.md}"
    padding: "0.5rem 0.75rem"
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.lg}"
    padding: "1.5rem"
  badge-success:
    backgroundColor: "{colors.success}"
    textColor: "{colors.success}"
    rounded: "{rounded.full}"
    padding: "0.125rem 0.5rem"
    typography: "{typography.label}"
  badge-danger:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.danger}"
    rounded: "{rounded.full}"
    padding: "0.125rem 0.5rem"
    typography: "{typography.label}"
  input:
    backgroundColor: "transparent"
    textColor: "{colors.text}"
    rounded: "{rounded.md}"
    padding: "0.5rem 0.75rem"
    height: "2.25rem"
---

# Design System: HESTIA

## 1. Overview

**Creative North Star: "The Examiner's Hearth"**

HESTIA is a workspace for people grading exams, not a SaaS dashboard pretending to be one. The palette starts from warm cream paper and bronze ink; the typography pairs a workhorse serif (Roboto, deployed at display weight) with a tight UI sans (Inter). Density is moderate, never cramped: rows breathe, points pills sit on their own air, and the carousel reveals one section at a time so the grader is never asked to scan twelve cards in parallel. The system rejects the dark-blue-glass-and-neon look that LMS and assessment tools default to.

Color is restrained. The gold-bronze primary carries the call to action and not much else; semantic roles (success, warning, danger) appear only where state is real (a section is ready, a score is missing, a delete is pending). The neutral cream surface does the heavy lifting. Dark mode is a true second theme: not "the same UI with inverted colors," but a deliberate rebalancing where the accent shifts from slate-blue to teal because the warm gold can no longer carry interest alone against a near-black surface.

**Key Characteristics:**
- Warm cream + bronze gold + earthy reads, not white + blue + chrome.
- Roboto display paired with Inter body; serif weight does the personality work.
- Subtle elevation: shadows are diffuse and rare, borders are tinted at 15–18% alpha.
- Single accent at a time per screen; semantic colors (success/warning/danger) gate themselves to state, not decoration.
- One screen, one focus: the section carousel and the grading panel both reject parallel-display patterns.

## 2. Colors

A warm-paper palette, anchored by a single bronze accent and three semantic roles that earn their visibility. Colors are defined in HSL in `src/index.css` (canonical); the frontmatter above carries hex equivalents for tooling compatibility.

### Primary
- **Bronze Gold** (`#865c1d`, light / `#D5B86C`, dark): The single brand voice. Carries the primary call-to-action (`Send to evaluation`, `Generate answer`), inline emphasis on hero text (`<span class="text-hestia-primary">`), and the focus ring. Reserved — when everything is gold, nothing is.

### Secondary
- **Deep Slate-Blue / Bright Teal** (`#386570`, light / `#06D6A0`, dark): The "in motion" accent. Used for the *Grading* status badge, the eyebrow tracking labels on the landing page, and other moments where the brand needs a second voice that isn't a semantic role. Theme-swap is intentional: slate-blue grounds the cream surface; teal lifts the dark surface.

### Semantic
- **Examiner's Green** (`#297a52`, light / `#5fc996`, dark): Success. A section is ready to confirm, an exam is finished, a 100% row in the breakdown table, the auto-graded answer badge. Forest-green, not emerald, because emerald reads as Tailwind-default and was the source of every drift we just polished out.
- **Vintage Amber** (`#D4880A`, light / `#F5A623`, dark): Warning. A task is missing a score, an evaluation is about to start that can't be undone, an intro slide that needs the user to read it.
- **Bookplate Red** (`#C0281F`, light / `#F24B6A`, dark): Danger. Destructive actions (delete section, delete figure block), the validation ring on an unset points field, the pending-grade badge.

### Neutral
- **Cream Paper** (`#e9e5db`, light / `#121212`, dark): App background. Never pure white in light mode; never pure black in dark mode.
- **Surface** (`#ffffff`, light / `#1e1e1e`, dark): Card and sidebar surface. The single elevated layer.
- **Dark Walnut** (`#2c2420`, light): Body text. A warm near-black tinted toward the primary hue. Dark mode flips to a parchment off-white (`#f0ece4`).
- **Text Muted** (`#2c2420` @ 65% alpha, light): Eyebrow labels, secondary metadata, placeholder text (which drops further, to 35%).
- **Border** (`#2c2420` @ 15% alpha, light / 18% alpha, dark): The default 1px hairline. Borders are tinted, never `currentColor`.

### Named Rules
**The Single Voice Rule.** No more than one of {primary, accent, semantic} reads on a given screen. If a Confirm button is success-green, the surrounding pills go to muted/danger; if the page is gold-led (landing hero), semantic state is held back until the user lands on a screen that genuinely has state.

**The No Raw Color Rule.** All color flows through `src/index.css` tokens. `emerald-500`, `red-500`, `amber-700`, `purple-500` are forbidden — if a semantic role doesn't exist for what you need, add a token to `index.css` (additive, not replacing) and expose it in `tailwind.config.ts`. The historical drift to raw Tailwind palettes is the canonical violation; it was polished out in May 2026.

## 3. Typography

**Display Font:** Roboto (with Georgia, "Times New Roman", serif fallback)
**Body Font:** Inter (with system-ui, -apple-system, "Segoe UI", sans-serif fallback)

**Character:** Roboto at 700 reads as a calm institutional serif — not a fashion display face, not a textbook face. Paired with Inter's tight UI metrics, the combination feels like a well-set academic monograph rather than a marketing landing page. The body face also enables `ss01` and `cv11` OpenType features (the disambiguated "1/I/l" glyphs) globally, because graders look at scores all day and ambiguity in numerals is a usability failure.

### Hierarchy
- **Display Headline** (Roboto 700, `clamp(2rem, 4vw, 3.5rem)`, line-height 1.1): Landing-page hero only. Combines a black-text fragment with an inline gold-primary fragment for emphasis.
- **Headline** (Roboto 700, `1.5rem–2.5rem`, line-height 1.15): Section titles on landing (`text-3xl md:text-[2.5rem]`), card titles in the editor (`text-2xl`).
- **Title** (Inter 600, `1rem`, line-height ~1.4): Section names in the carousel, card headings inside the grading interface.
- **Body** (Inter 400, `0.875rem`, line-height 1.5): Default text in every editor surface, table rows, dialog content. Cap at 65–75ch in long-form copy.
- **Body Large** (Inter 400, `1.125rem`, leading-relaxed): Landing hero subtext only.
- **Label / Eyebrow** (Inter 500, `0.625rem`, uppercase, `letter-spacing 0.05em`): The micro-typography used everywhere — eyebrow labels above section inputs, points-pill content, status badges, dropdown trigger labels. Defined globally as `text-[10px] font-medium uppercase tracking-wider text-hestia-text-muted`. Sub-token by design; do not promote to `text-xs` to "normalize", because the scale is part of the visual signature.

### Named Rules
**The Scholarly Numerals Rule.** All number-bearing text (scores, points, percentages, table cells) uses `tabular-nums`. Score columns must not visually shift width as values change between rows.

**The Eyebrow Rule.** Uppercase labels are reserved for metadata (point counts, status, section indices) and never used for prose. They run at the 10px scale; they do not migrate up to the body scale to "match" other labels.

## 4. Elevation

Mostly flat. Three shadow primitives exist (`hestia-shadow-sm/md/lg`) but only `sm` is in regular use — on cards and on the primary CTA. `md` shows up on hover for a few list items; `lg` is reserved for the expanded sidebar dialog. Depth is mostly conveyed through tinted borders (15–18% alpha) and the cream-surface-on-cream-bg contrast, not through shadows.

### Shadow Vocabulary
- **Resting** (`box-shadow: 0 1px 2px hsl(0 0% 0% / 0.06)`): Card resting state, primary CTA resting state. Almost imperceptible — its job is to lift cards off the cream background just enough.
- **Lifted** (`box-shadow: 0 4px 12px hsl(0 0% 0% / 0.08)`): Exam cards on hover in the list view; toast notifications. The single "something is hover-interactive" tell.
- **Floating** (`box-shadow: 0 8px 24px hsl(0 0% 0% / 0.12)`): Only the expanded-sidebar dialog and dropdown menus. Reserved for true overlay surfaces.

### Named Rules
**The Flat-By-Default Rule.** Resting surfaces have shadow `sm` or no shadow at all. Hover may bump to `md`. Reaching `lg` requires the surface to be an actual floating overlay (dialog, dropdown, popover). No `lg` shadows on cards-in-flow.

**The Hairline Rule.** Borders are `1px solid hsl(var(--hestia-border))` — that's text color at 15% alpha in light, 18% in dark. Never `border-2`+colored as decoration; that weight is reserved for the carousel's active-grading panel where it earns the emphasis.

## 5. Components

Components are imported from `src/components/ui/*` (shadcn primitives, managed by the shadcn CLI — do not hand-edit). Feature-level composition lives in `src/components/exam-edit/`, `src/components/exam-results/`, and `src/components/landing/`.

### Buttons
- **Shape:** Rounded square (radius `md` = 8px for compact, `md` for primary CTAs). Never full-rounded except on pills.
- **Primary** (`bg-hestia-primary text-white`, padding `py-3 px-5` for hero, `py-2 px-3` for inline): The single accent voice on a page. Hover transitions `background` to `--hestia-primary-hover`.
- **Success** (`bg-hestia-success text-white hover:bg-hestia-success/90`): Confirm / Send-to-evaluation actions only. Not "save", not "submit" generically — those stay primary-gold.
- **Danger** (`bg-hestia-danger text-white hover:bg-hestia-danger/90`): Destructive `AlertDialog` action buttons.
- **Ghost / Inline** (transparent, `text-hestia-primary hover:underline`): Secondary inline link on landing; "Back to exams" in editor header.
- **Disabled state:** `bg-hestia-primary/40 cursor-not-allowed`, never grey. A disabled primary button stays gold, just muted.

### Badges & Status Pills
- **Shape:** `rounded-full`, `px-hestia-2 py-0.5`, label typography (10px uppercase tracking-wider).
- **Variants:** success / danger / warning use the matching `bg-hestia-{role}/10 text-hestia-{role} border border-hestia-{role}/40` pattern. Primary-muted variant (`bg-hestia-primary-muted/30 text-hestia-text-muted`) carries informational tags like model name.
- **The exam-status badge** (`src/components/ExamStatusBadge.tsx`) is the canonical implementation; new status pills must match its prop shape.

### Cards
- **Corner Style:** `rounded-hestia-lg` (12px).
- **Background:** `bg-hestia-surface`.
- **Shadow:** `shadow-hestia-sm` resting; hover may bump to `shadow-hestia-md` for list-card affordances.
- **Border:** `border border-hestia-border` — always present, always hairline.
- **Internal Padding:** `p-6` for shadcn `Card`, `p-hestia-5` (24px) for the custom `hestia-card` utility. The two are equivalent; prefer `Card` from `@/components/ui/card` for new code per CLAUDE.md.
- **No nesting:** never a card inside a card. The grading panel (`TaskGradingPanel`) uses a tinted `bg-hestia-primary-muted/5` surface with a `border-2 border-hestia-primary/30` instead of a nested card; that is the established pattern.

### Inputs
- **Style:** `bg-transparent` with `border border-hestia-border`, radius `md`, height `h-9` (36px) for compact, `h-10` for default.
- **Focus:** 2px outline at `--hestia-primary` with 2px offset (defined globally on `*:focus-visible` in `index.css`). Do not override per-component.
- **Error state:** `border-hestia-danger ring-1 ring-hestia-danger/30`. Used for missing-points validation in `TaskCard` and the pending-score field in `TaskGradingPanel`.
- **Placeholder:** `--hestia-text` at 35% alpha (defined globally in `index.css`; lighter than muted body text to read as hint).

### Section Card (signature component)
The `SectionCard` (`src/components/exam-edit/SectionCard.tsx`) uses a 3px status-driven left border (`borderLeft: 3px solid var(--hestia-{success|primary|border})`) keyed to draft / ready / confirmed state. **This is a deliberate exception to the no-side-stripe rule**: in the editor carousel, only one section is visible at a time, so a colored left edge becomes a persistent peripheral cue (am I done with this section?) rather than a decorative accent on a card grid. New components do not get to claim the same exception. If you find yourself wanting a colored left border on a card grid or list item, you are violating the rule, not extending the pattern.

### Sidebar & Navigation
- **Sidebar** (`src/components/exam-edit/ExamSidebar.tsx`): Surface-colored, collapsible to icon-only width. Item rows use `bg-hestia-primary-muted/40` on hover. Active row uses the sidebar primitive's built-in active state.
- **Carousel pagination** (`SectionCarousel.tsx`): Traffic-light row of pill indicators — success (confirmed) / warning (ready) / danger (needs work). Active pill widens, inactive pills are dots. Section step counter sits inline.

## 6. Do's and Don'ts

### Do:
- **Do** flow all color through `--hestia-*` tokens in `src/index.css` and the `hestia` Tailwind color extension. If a semantic role is missing, add a token; don't reach for `emerald-500`.
- **Do** use `font-display` (Roboto) only on headings; body copy stays Inter.
- **Do** reserve the gold primary for the single most important call-to-action per screen. If two buttons feel like they should both be primary, one of them is wrong.
- **Do** use `tabular-nums` on every score, percentage, point count, and table cell carrying a number.
- **Do** keep cards single-layer: tinted surfaces inside a card, not cards inside cards.
- **Do** use `hestia-space-*` tokens for padding and gap.
- **Do** style validation errors as `border-hestia-danger ring-1 ring-hestia-danger/30`.
- **Do** test every screen in both light and dark themes; the accent token swap is real and changes the visual hierarchy.
- **Do** prefer composing shadcn primitives from `src/components/ui/` over building new chrome.

### Don't:
- **Don't** use Tailwind's named color palettes (`emerald-*`, `red-*`, `amber-*`, `purple-*`, `blue-*`). Every one of these is a drift signal, not a design choice.
- **Don't** use `border-left` or `border-right` greater than 1px as a colored stripe on new components. The only exception is `SectionCard`, which is grandfathered for the carousel-context reason above.
- **Don't** use `background-clip: text` with a gradient. Gold-on-cream emphasis is handled with a solid `text-hestia-primary` span inside a default heading.
- **Don't** use `backdrop-blur` decoratively. It exists in exactly two places (grading-panel loading overlay, dialog overlay) — both legitimate uses.
- **Don't** introduce purple, neon, or cool-grey ramps. Purple was the off-brand "grading" status; it's now `hestia-accent`. If you find yourself wanting purple, you want accent.
- **Don't** promote `text-[10px]` eyebrow labels to `text-xs` "for consistency." The 10px scale is part of the visual signature; the 12px scale belongs to body-secondary text.
- **Don't** use raw `#fff` / `#000`. The surface tokens are tinted (`#e9e5db`, `#2c2420`); pure black/white reads cheap against them.
- **Don't** add a Card inside a Card. Use tinted backgrounds with borders if you need visual grouping inside a container.
- **Don't** style the focus ring per-component. It's defined globally on `*:focus-visible`.
- **Don't** hand-edit files in `src/components/ui/` — they're owned by the shadcn CLI.
