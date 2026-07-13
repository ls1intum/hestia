# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this
directory (`apps/landing-page`).

## What this app is

The **HESTIA root landing page** — a standalone, backend-less React SPA that serves the bare
host root (`https://<vm-host>/`) of the HESTIA VMs. It markets the HESTIA tool family
(ExamLense, learning-goal extraction, active-teaching design) and collects newsletter
signups and teaching-material donations. **Bilingual (DE/EN)** with a header toggle; no API.
The only "page" beyond the landing content is the **Impressum**, served client-side via the
hash route `#/impressum` — there is still no router library.

Unlike the other apps it owns **no path prefix**: its Traefik router matches
``Host(`${APP_HOST}`)`` at priority 1, so `/examlense`, `/learninggoalhub` etc. always win
and the landing page catches the rest. The Vite base is therefore always `/`. See
`DEPLOY.md` and `infra/traefik/README.md`.

## Commands

Run from this directory (`apps/landing-page/`):

```bash
npm run dev          # Vite dev server at http://localhost:8090
npm run build        # Production build → dist/
npm run lint         # ESLint check
npm run preview      # Preview the production build locally
```

There are no tests (static marketing page). Verify changes visually against both themes.

## Architecture

React 18 + TypeScript, Vite (SWC), Tailwind CSS 3. Only runtime deps are `react` and
`react-dom` — no router, no React Query, no component library. Keep it that way unless a
real need appears.

- `src/App.tsx` — composes the page: `SiteHeader → Hero → VisionSection → PipelineSection → MaterialSection → SiteFooter`. A tiny `useHashRoute` hook swaps the landing sections for `ImpressumPage` when the hash is `#/impressum` (header/footer stay mounted); also renders `TestSystemBanner`.
- `src/components/` — one file per page section, plus:
  - `ui/` — minimal local `Button` / `Input` / `Alert` primitives (this app does NOT use shadcn)
  - `NewsletterForm.tsx` — shared by hero and footer (`variant="hero" | "footer"`); submits to the Listmonk public subscription form via a no-cors `fetch` (empty honeypot `nonce`), then shows an optimistic success `Alert`
  - `ImpressumPage.tsx` — the bilingual Impressum, rendered from `t.imprint.sections`
  - `TestSystemBanner.tsx` — fixed top-left "Testsystem/Test system" marker; hidden when `IS_PRODUCTION`
  - `PlaceholderBadge.tsx` — visible dashed "Platzhalter · …" pill marking still-unwired integrations (Datenschutz, Datenkonzept)
  - `HestiaWordmark.tsx` — theme-dependent logo (`src/assets/hestia-wordmark-{light,dark}.svg`)
- `src/i18n/` — `de.ts` is the source of truth; `Dictionary = typeof de` forces `en.ts` to
  match. `src/hooks/use-language.tsx` provides `LanguageProvider`/`useI18n()`: stored choice
  (`localStorage["hestia-language"]`) wins, else browser locale; mirrors the theme system.
- `src/config.ts` — **the single wiring point** for external integrations: Listmonk endpoint +
  list UUID, Nextcloud upload URL, contact email, and `VITE_ENVIRONMENT`. All overridable via
  `VITE_` env vars at build time; defaults are the real production values. When a still-unwired
  integration lands, set it here and remove the corresponding `PlaceholderBadge` at the call site.
- `src/hooks/use-theme.tsx` — `ThemeProvider`/`useTheme()`: follows the OS preference until
  the header toggle stores an explicit choice (`localStorage["hestia-theme"]`). An inline
  script in `index.html` pre-sets `data-theme` on `<html>` to avoid a theme flash.

## Styling / design tokens

The **canonical HESTIA brand palette** lives in `src/index.css` as CSS variables
(`--hestia-bg: #faf8f4`, `--hestia-primary: #946520` gold, `--hestia-accent: #447a86` teal,
dark values under `[data-theme="dark"]`). Derived states (hover/muted/border) use
`color-mix` — **never invent new hex values**. `tailwind.config.ts` maps the variables to
utilities (`bg-hestia-bg`, `text-hestia-primary`, `rounded-hestia-lg`, `shadow-hestia-sm`,
`font-display`…).

Fonts: Playfair Display (h1–h3 only), Inter (everything else), JetBrains Mono (code/labels)
— loaded from Google Fonts in `index.html`.

Note: these token values are newer than examlense's `index.css` tokens; do not copy values
from other apps into here (or vice versa) without checking.

## Copy / strings

Bilingual DE/EN via the header toggle. All copy lives in `src/i18n/de.ts` (source of truth)
and `src/i18n/en.ts` — the `Dictionary` type keeps them in sync (TS errors on drift). The
German wording came from the approved design reference — don't rephrase it as a side effect
of refactoring, and add every new string to **both** dictionaries.

## Deployment

Single nginx container (`Dockerfile` + `nginx.conf`, no proxy), composed by
`compose.prod.yaml` (only env vars: `APP_HOST`, `IMAGE_TAG`). nginx serves real files and
**302-redirects any unknown path to `/`** (there are no deep server paths — the Impressum
route is hash-based); app prefixes are handled upstream by Traefik and never reach this
container. Set `VITE_ENVIRONMENT=production` at build time on the Production build to hide
the test-system banner. CI is `.github/workflows/landing-page-cicd.yml` (PR → build
`pr-<N>`; main → Staging; release → Production). Details in `DEPLOY.md`.
