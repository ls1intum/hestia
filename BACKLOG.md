# Backlog

Cross-app to-dos that don't belong to a single app. App-specific tasks stay in the app.

## Data protection

The privacy policy (`apps/landing-page/src/i18n/de.ts` / `en.ts`) already describes the
behaviour below. Until these are built, the tools do not match the published policy.

- [ ] **Upload confirmation** — the first time a user uploads content, they must tick a
      checkbox confirming that the upload contains no personal data (e.g. student names,
      student ID numbers).
- [ ] **Separate consent for external LLMs** — every use of a language model outside Logos
      (Gemini, OpenAI, Anthropic, …) needs its own opt-in consent, since it is an external
      service with transfer to the USA. Without it, only Logos may be used.
- [ ] **Consent log** — log every consent (who, what, which policy version, timestamp) and
      every withdrawal, so consent can be proven (Art. 7(1) GDPR).
- [ ] **Inactivity deletion** — built into the tools: delete accounts and their content
      after 365 days without sign-in; warn users at 300 days and again at 350 days. A sign-in
      resets the timer.
- [ ] **Migrate from SAIA (GWDG) to Logos** — LearningGoalHub and Workshopper still call
      SAIA / Chat AI (`chat-ai.academiccloud.de`), including the shared default in
      `libs/shared-llm` (`HestiaLlmDefaults`), their `compose.prod.yaml`, `.env.example`,
      `application.yml`, the LearningGoalHub CI/CD workflow and their docs. ExamLense has
      already dropped its GWDG endpoint, but its `server/.env.example` still points at
      `chat-ai.academiccloud.de`. Work in progress on branch `feat/logos-provider` (Logos
      as the `shared-llm` default, SAIA behind a profile).
