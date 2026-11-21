# Backlog

Cross-app to-dos that don't belong to a single app. App-specific tasks stay in the app.

## Data protection

The privacy policy (`apps/landing-page/src/i18n/de.ts` / `en.ts`) already describes the
behaviour below. Until these are built, the tools do not match the published policy.

- [x] **Upload confirmation** — the first time a user uploads content, they must tick a
      checkbox confirming that the upload contains no personal data (e.g. student names,
      student ID numbers). Done in `core` (`server/docs/privacy.md`): uploads answer 428
      `UPLOAD_CONFIRMATION_REQUIRED` until confirmed, logged append-only in `core.upload_confirmation`.
- [x] **Separate consent for external LLMs** — every use of a language model outside Logos
      (Gemini, OpenAI, Anthropic, …) needs its own opt-in consent, since it is an external
      service with transfer to the USA. Without it, only Logos may be used. Done in module `ai`:
      the gateway refuses external models without consent, asked when such a model is used.
- [x] **Consent log** — log every consent (who, what, which policy version, timestamp) and
      every withdrawal, so consent can be proven (Art. 7(1) GDPR). Done: `ai.consent_event`
      (append-only).
- [x] **Inactivity deletion** — built into the tools: delete accounts and their content
      after 365 days without sign-in; warn users at 300 days and again at 350 days. A sign-in
      resets the timer. Done in `core` (`server/docs/privacy.md`): daily job, warnings shown after
      the next login. Open: e-mail delivery of the warnings (Hestia stores no address), and the
      privacy policy should mention that intake submissions follow the intake terms instead.
      Runs as a dry run on every deploy target until the e-mail question is decided
      (`HESTIA_INACTIVITY_DRY_RUN`, operations manual).
- [ ] **Inactivity: dry run never reports a deletion** — a dry run stores no warning, so the
      deletion check (last warning older than 15 days) never passes and the log never says what
      would be deleted. Treat the would-be warning as present, or log "would delete on <date>".
- [ ] **Inactivity: advisory lock can stay on a pooled connection** — if `pg_advisory_unlock`
      throws, the session lock stays on a connection that goes back to the pool and blocks later
      runs. Abort that connection instead of returning it, or switch to ShedLock.
- [ ] **Inactivity: a login racing a deletion gives one 500** — the login waits for the user row
      lock, then hits `StaleStateException` in `IdentityService` (line ~55) because the row is
      gone. Retry the login once (it then creates a fresh account) or answer 401.
- [ ] **Upload confirmation: client dialogs** — two uploads answering 428 at once hang, because
      ExamLense and Workshopper share one `answer.current` ref; share one pending promise between
      callers instead. A 409 for an outdated notice is a dead end: reload the notice and ask again.
- [ ] **Decision for Ben: confirmations of deleted users** — consents and upload confirmations go
      with the account (cascade), while the account's uploads in shared courses stay. Either say
      so in `privacy.md` and the privacy policy, or keep a pseudonymised row as proof.
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
- [ ] **Role checks in every `AlignmentApi` method** — done for `link`/`unlink` (`EDITOR` in the
      goal's course), `goalsFor*` (only links of readable courses), `openReviews` and `confirm`
      (package 7). Left: `unlinkTarget` and `unlinkTargetInCourse` still check nothing and rely on
      the calling module (they also run inside course deletion and moves).
- [ ] **Optimistic locking for sessions and exams** — no `@Version`: `WorkshopService.saveDraft`
      and `ExaminationController.patch` with a stale `courseId` can undo a move.
- [ ] **Event publication registry** — `core.events` (`GoalChanged`, `GoalRemoved`,
      `CourseDeleted`) are plain Spring events; review marks are written by a synchronous core
      listener in the same transaction. Add `spring-modulith-starter-jpa` with the
      `core.event_publication` table once a domain module listens with
      `@ApplicationModuleListener`.
- [ ] **Review marks after one's own edit** — editing a goal's text in Workshopper's goal step marks
      that session's own link for review too. Confirm the editing session's link right away, or
      leave as is (the other links of the goal must stay marked).
- [ ] **Removed goals in a session plan** — a plan entry whose goal was removed disappears from the
      resolved plan; its link stays marked (saving keeps links in review) and shows in the course's
      review list until confirmed. Show it in the plan with a "removed" mark and let `toStored`
      accept it.
- [ ] **Whitespace and label edits create reviews** — `SessionGoals.saveCourseGoals` compares the
      text without normalising whitespace (materials strips it), and `CoreGoalVersion.sameContent`
      counts a change of only the short label as a content change, so such edits mark all links.
- [ ] **"Reviewed" for viewers** — viewers see the confirm buttons (`ReviewListDialog`,
      `WorkshopGoalEntry`, ExamLense `GradingView`) and get a 403 toast. Hide them by course role,
      and reload the list after 404 as already after 409.
- [ ] **Updating a removed goal** — `GoalService.update` still versions a removed goal
      (`writable()` does not check `removedAt`); answer 400 instead.
- [ ] **Review marks in more views** — ExamLense shows them only in the grading view; LearningGoalHub
      shows no review list (documents' links are marked as well).
- [ ] **Smaller points:**
      - `alignment_link.course_id` is not tied to the goal's course by a foreign key.
      - Deleting the personal course answers 500 (`IllegalArgumentException`) instead of 400/409.
      - Two concurrent `LearningGoalApi.update` calls on one goal answer 500 instead of 409.
      - `WorkshopService.saveDraft` is not transactional.
      - Course lists stop at `listMine(0, 1000)`.
      - Migration V14 deleted all `teaching.slide_templates` (disposable data at the time).

## Prototype run-through (follow-ups from a click-through of the whole flow on staging)

- [ ] **Exam goals do not reuse the course's goals** — confirming a section derives new goals
      (origin `EXAM`) for its tasks instead of linking the tasks to matching goals the course
      already has from its material. Slides, sessions and exam tasks therefore never share a goal,
      and "which goal does no task assess" cannot be answered across the three apps. Match derived
      goals against the course's goals first (link, or propose a link) and create a goal only for
      what is left.
- [ ] **Review mark hidden in ExamLense** — the "review" badge of a task's goal is only visible
      after expanding "Learning goals (n)" in the grading view; the collapsed header shows nothing.
      Show a marker on the collapsed header (and open it when a goal is marked).
- [ ] **Goal picker in Workshopper** — the Goals step lists every goal of the course as one flat
      list (100+ for a course with seven slide decks) above the session's own goals, so the
      session's goals and their review notices sit at the very bottom. Group the picker by topic,
      make it collapsible or searchable, and show the session's goals first.
- [ ] **Adding material to an existing course** — LearningGoalHub uploads documents only in the
      create-course dialog; the course page offers no upload for more material.
- [ ] **Parser model per deployment** — `ParserStrategies.DEFAULT_ID` is fixed; a deployment whose
      account for the default provider is empty has to rely on the fallback model in
      `ParseExaminationService.extractWithFallback` on every parse. Make the default configurable.
- [ ] **Times in Workshopper** — a course created in LearningGoalHub has no `createdAt` in
      `/api/workshop/courses` and shows "Created 1. Jan."; session times are sent without an offset
      (`2026-…T00:39:05`) and read as local time, so a session created minutes ago shows "1h ago".
- [ ] **Mixed languages** — the review labels are German in ExamLense ("prüfen", "Geprüft") and
      English in Workshopper ("review", "Reviewed") in otherwise English apps; a session planned
      from German goals gets English block titles.
- [ ] **Course deletion warning without numbers** — LearningGoalHub's dialog says that sessions and
      exams go with the course but not how many; Workshopper's names them.
- [ ] **Results before grading** — ExamLense's results view redirects to grading until every task
      is graded, so the per-goal overview cannot be shown for a partly graded exam.

## Login federation (follow-ups from the review of package 8)

- [ ] **User identity per identity provider** — users are keyed by (organisation, externalId), not
      by the issuing identity provider's entityID: two identity providers that share a scope share
      accounts, and a transient NameID (TUM login fallback) makes a new user on every login. Store
      the issuer with the user and prefer `subject-id`/`pairwise-id` where an identity provider
      releases them.
- [ ] **Who may create an organisation** — every member of the federation (also institutes and
      companies, not only universities) can create an organisation by logging in. Consider an
      allowlist or a registered entity category (e.g. research and scholarship), or at least a log
      entry or alert when a new organisation appears.
- [ ] **Rate limit for `/saml2/authenticate/**`** — the login start is not rate-limited. A loop fills
      the in-memory request store (10,000 entries) and evicts real users' requests in flight. Limit
      it per client address like the LLM endpoints.
