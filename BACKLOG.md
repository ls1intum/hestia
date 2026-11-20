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

## Core data model (follow-ups from the review of package 6)

- [ ] **Personal course without membership** — `CourseService.personalCourse()` answers `OWNER`
      without checking the membership, so a creator removed from their own catch-all course
      still gets it back as owner. Check the membership (or refuse removing the last owner of a
      personal course).
- [ ] **Role checks in every `AlignmentApi` method** — `link` needs only read access to the
      goal's course; `unlink*` and `goalsFor*` check nothing and rely on the calling module.
      Check course roles in core as the other core APIs do.
- [ ] **Optimistic locking for sessions and exams** — no `@Version`: `WorkshopService.saveDraft`
      and `ExaminationController.patch` with a stale `courseId` can undo a move.
- [ ] **Smaller points:**
      - `alignment_link.course_id` is not tied to the goal's course by a foreign key.
      - Deleting the personal course answers 500 (`IllegalArgumentException`) instead of 400/409.
      - Two concurrent `LearningGoalApi.update` calls on one goal answer 500 instead of 409.
      - `WorkshopService.saveDraft` is not transactional.
      - Course lists stop at `listMine(0, 1000)`.
      - Migration V14 deleted all `teaching.slide_templates` (disposable data at the time).
