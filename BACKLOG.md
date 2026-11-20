# Backlog

Cross-app to-dos that don't belong to a single app. App-specific tasks stay in the app.

## Data protection

The privacy policy (`apps/landing-page/src/i18n/de.ts` / `en.ts`) already describes the
behaviour below. Until these are built, the tools do not match the published policy.

- [ ] **Upload confirmation** — the first time a user uploads content, they must tick a
      checkbox confirming that the upload contains no personal data (e.g. student names,
      student ID numbers).
- [x] **Separate consent for external LLMs** — every use of a language model outside Logos
      (Gemini, OpenAI, Anthropic, …) needs its own opt-in consent, since it is an external
      service with transfer to the USA. Without it, only Logos may be used. Done in module `ai`:
      the gateway refuses external models without consent, asked when such a model is used.
- [x] **Consent log** — log every consent (who, what, which policy version, timestamp) and
      every withdrawal, so consent can be proven (Art. 7(1) GDPR). Done: `ai.consent_event`
      (append-only).
- [ ] **Inactivity deletion** — built into the tools: delete accounts and their content
      after 365 days without sign-in; warn users at 300 days and again at 350 days. A sign-in
      resets the timer.
- [x] **Migrate from SAIA (GWDG) to Logos** — all chat and vision calls run on Logos through
      module `ai`; `libs/shared-llm` is gone.

## AI

- [ ] **Bring your own key for external models** — let users run ExamLense's benchmark against
      their own provider account (their OpenAI/Anthropic/Gemini key) instead of the project's.
      Logos stays the central gateway for everything else. Not needed yet.
