# Privacy features of core

Two features the published privacy policy describes live in `core` (package
`core.internal.privacy`, endpoints under `/core/api/privacy`):

```bash
./gradlew :server:test --tests 'de.tum.cit.hestia.core.internal.privacy.*'   # needs a container runtime
```

## Upload confirmation

Before their first upload in any of the three tools, users confirm once that uploads contain no
personal data of others (for example student names or ID numbers).

- **Server-side check.** Every endpoint that accepts an upload calls
  `core.UploadConfirmation.requireConfirmed()` after its access check and before it stores
  anything: LearningGoalHub documents, ExamLense exam PDFs and figures, Workshopper slide templates
  (session, course, and a template sent with an export). Without a confirmation of the current
  notice version the answer is `428` with `code: UPLOAD_CONFIRMATION_REQUIRED`.
  The intake page (`/learninggoalhub/start`) is not checked: its terms, which the submitter accepts
  with every submission, already contain this confirmation.
- **Log.** `core.upload_confirmation` (migration V16) is append-only for the application
  (`update`/`delete` revoked), like `ai.consent_event`: user, notice version, time. Deleting a user
  removes their rows by cascade.
- **Text.** `resources/core/upload-confirmation-<version>.md` plus the checkbox label in
  `UploadConfirmationService`. A changed text gets a new `NOTICE_VERSION`; users are asked again.
- **API.** `GET /core/api/privacy/upload-confirmation` (notice and status),
  `POST` with `{"noticeVersion": ...}` (409 for an outdated version).
- **Clients.** ExamLense and Workshopper show the dialog when an upload answers 428 and repeat the
  upload after the confirmation. LearningGoalHub asks before it creates the course, so cancelling
  leaves no empty course behind.
- `hestia.privacy.upload-confirmation.enforced=false` turns the check off; only the test
  configuration does that (the module tests upload without a confirmation).

## Inactivity deletion

Accounts without a login for a year are deleted with their content; users are warned before.

| Setting (`hestia.privacy.inactivity.*`) | Default | Meaning |
| --- | --- | --- |
| `enabled` (`HESTIA_INACTIVITY_ENABLED`) | `true` | the daily run happens at all |
| `dry-run` (`HESTIA_INACTIVITY_DRY_RUN`) | `true` | only log what would be warned and deleted |
| `warn-after-days` | `300,350` | days without login after which a warning is recorded |
| `delete-after-days` | `365` | days without login after which the account is deleted |
| `cron` | `0 30 3 * * *` | when the run starts (server time) |
| `notifier` | `log` | how a warning is delivered besides the notice after login (see below) |

The deployment passes `HESTIA_INACTIVITY_ENABLED` and `HESTIA_INACTIVITY_DRY_RUN` through
(`infra/compose.prod.yaml`, set as GitHub environment variables). Dry run is the default on every
deploy target for now: a user who does not log in never sees the in-app warning, so real deletions
wait for the decision on how warnings reach users outside the app.

How a run works (`InactivitySweep`):

- **Dry run.** Logs the warnings and deletions a real run would make and writes nothing.
- **One instance.** The run takes a Postgres advisory lock; a second instance skips.
- **Per organisation.** It sets `OrgContext` for each organisation, so the tenant filter and
  row-level security apply as in a request; each user is handled in a transaction of its own that
  locks the user row first (a login waits for it) and checks again.
- **Warnings.** A user whose last login is at least a threshold ago gets one
  `core.inactivity_warning` row (migration V17) per threshold and inactive period. A row belongs
  to the period that began at its `last_seen_at`; a login moves `app_user.last_seen_at` and so
  starts a new period, which is how a login resets the timer.
- **Deletion** happens only once the last warning has stood for the gap to the deletion (default
  15 days), so nobody is deleted unwarned, also after the job was off for a while.
  `core.internal.course.CourseRelease` handles the courses: a course without other members (the
  personal one included) is deleted through the same path as `CourseApi.delete` (module hooks,
  stored files after the commit). A course with other members stays with all its content; if the
  user was its only owner, the longest-standing editor becomes owner, else the longest-standing
  viewer, and a shared personal course becomes an ordinary course. Then the `app_user` row goes;
  the database removes memberships, consents and user-private objects and sets `created_by` to null.
  Intake submissions are kept: they are not linked to the account and have their own retention in
  the intake terms.
- **Notice after login.** `GET /core/api/privacy/inactivity-notices` lists the latest
  unacknowledged warning; the clients show it once after login and acknowledge it
  (`POST .../inactivity-notices/acknowledge`).
- **Notifier.** `InactivityNotifier` is called after a warning was committed. The default (`log`)
  logs ids only. `mail` is a placeholder that refuses to start: Hestia stores no e-mail address and
  no mail server is configured, so e-mail delivery needs a decision first.
