# AI module

The `ai` module of the hestia server is the only place that talks to a language model. The domain
modules (`materials`, `teaching`, `assessment`) call it through its public types in
`de.tum.cit.hestia.ai`; everything below `ai.internal` is private to the module. It has no
controllers and no URL prefix: a module that needs an endpoint (for example ExamLense's consent
dialog) adds a thin controller of its own.

```bash
./gradlew :server:test --tests 'de.tum.cit.hestia.ai.*'   # from the repository root; needs a container runtime
```

## Where calls go

| Provider | Used for | Consent | Settings |
| --- | --- | --- | --- |
| Logos (TUM AET gateway, OpenAI-compatible) | everything by default: chat and vision in materials and teaching | not needed | `spring.ai.openai.*`, key `LOGOS_API_KEY`; defaults in `ai.internal.HestiaLlmDefaults` |
| OpenAI, Anthropic, Google Gemini (direct) | only models Logos does not offer: ExamLense's benchmark models | required | `hestia.ai.{openai,anthropic,gemini}.*` in `modules/ai.yml`, keys `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY` (blank = provider off) |

The provider host names appear only in `ai.internal` and the configuration.

## API

- `LlmGateway.chat(LlmRequest)`: one call. The request names the calling `module`, a `purpose`, the
  core course if any, the job it belongs to, the model (`ModelRef`; default Logos model when
  omitted), the prompt (`LlmContent`: text, images as data URLs, files) and, for the direct
  providers, a tool that forces structured output. `LlmResponse` carries the text or the tool
  arguments and the token usage.
- `LlmJobs`: a job is one unit of work a person starts (read in an exam, let one model solve it),
  however many calls it takes. `check`/`admit` enforce per-user limits over a rolling 24 hours
  (`hestia.ai.job-limits.<purpose>`, 0 = off; `exam-parse` 10 and `exam-solve` 30 by default, env
  `HESTIA_AI_LIMIT_EXAM_PARSE` / `HESTIA_AI_LIMIT_EXAM_SOLVE`). Calls name their job, so tokens add up
  per job.
- `ConsentApi`: the logged-in user's consent to external models (`ConsentScope.EXTERNAL_MODELS`):
  notice, status, grant, revoke.
- `ModelCatalog`: the configured Logos models and which direct providers have a key.

## Guarantees

- **No external call without consent.** A call to a direct provider is refused with
  `ConsentRequiredException` (HTTP 428) before anything leaves the server, unless the call's user
  currently consents. The user is the job's (background work) or the logged-in one; a call with
  neither is refused. Admitting a job for an external model checks the same.
- **Consent log.** `ai.consent_event` is append-only (the application role may not update or delete
  rows); the current state is a user's latest row. A consent counts only for the notice version it
  was given for: the notice text is versioned in `resources/ai/consent-external-models-<version>.md`,
  and a changed text gets a new version and asks again.
- **Cost attribution.** Every answered call is written to `ai.llm_call` (job, user, course, module,
  purpose, provider, model, prompt and completion tokens), every admitted job to `ai.llm_job`.
- Background work must carry `OrgContext` (as everywhere); without it a call is not logged and an
  external call is refused.

## Tables

`ai.llm_job`, `ai.llm_call`, `ai.consent_event` (migration `V10__ai_baseline.sql`), each with
`org_id` and row-level security like every table.
