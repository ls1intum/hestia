# Model calls

Every model call LearningGoalHub makes, which settings drive it, how failures are handled, and how
runs stay comparable. To add a call, follow the steps in
[server/README.md](../server/README.md#an-llm-call).

## Gateway and models

All calls go to the TUM AET Logos gateway through its OpenAI-compatible API
(`spring.ai.openai`, key in `LOGOS_API_KEY`). The defaults come from
[`libs/shared-llm`](../../../libs/shared-llm/README.md), which Workshopper uses too:
`openai/gpt-oss-120b` for text and `Qwen/Qwen3.8-27B` for images. A call that names no model
gets the text default.

Every call goes through `llm/LlmClient`. It applies the retry schedule, the HTTP timeouts and the
token count to all of them alike, and binds structured replies with `LenientJson`. No other class
builds a `ChatClient`.

## The calls

Settings are under `hestia.*` in `server/src/main/resources/application.yml`. UI tier names are
used here; the code calls the Skill tier "capability" and the Sub-skill tier "skill".

| Call | Class | When | Model setting | Temperature | Reply |
| --- | --- | --- | --- | --- | --- |
| Bookmark judge | `BookmarkRelevanceJudge` | upload, only for PDFs whose bookmarks are ambiguous | `bookmark.judge-model` | provider default | text: split or one session |
| Page description | `PageDescriptionService` | extraction, text-poor pages of courses with "Analyse slide images" on | `figures.vision-model` | provider default | list of page descriptions |
| Document title | `DocumentTitleService` | upload of a bookmark-less PDF; off (`title.enabled: false`) | `title.vision-model` | provider default | text: the title |
| Unit extraction | `SessionExtractionService` | extraction, one call per unit of at most 12,000 characters, plus one retry when the reply fails validation | text default | `extraction.temperature` (0.2); the retry 0.0 | sub-skills with knowledge, Bloom, SOLO, quotes |
| Topic tree | `TopicTreeSynthesizer` | after extraction and on rebuild: name topics, assign sub-skills, structure each topic, shorten labels | `extraction.taxonomy-planner-model` | `extraction.assignment-temperature` (0.0) | indices and names |
| Classification | `TaxonomyService` | goals made outside extraction: Topic tree skills, exam goals, "Find in the slides", "Generate with AI" | text default | `extraction.assignment-temperature` (0.0) | Bloom and SOLO per goal |
| Exam goals | `ExamGoalGenerator` | ExamLens request, one call per exam task | text default | `extraction.temperature` (0.2) | goals for the task |
| Topic search | `TopicSearchSynthesizer` | "Find in the slides": search terms for the topic, then duplicates against the course | `extraction.taxonomy-planner-model` | `extraction.assignment-temperature` (0.0) | terms; indices of duplicates |
| Generate with AI | `SubtreeSynthesizer` | "Generate with AI" on a new topic | text default | `extraction.temperature` (0.2) | skills, sub-skills and knowledge, without sources |

"Find in the slides" also runs the unit extraction on the pages it found.

Extraction stays at 0.2 because at 0 a model can repeat the same omissions in every run. Calls
that pick from a given list (assignment, classification, duplicates) run at 0. Goals added by hand
stay unclassified.

## When a call fails

- **Retries.** `429` and `5xx` answers are retried up to 8 attempts, with backoff starting at 1 s,
  doubling, capped at 60 s, plus random jitter so parallel workers do not retry together
  (`spring.ai.retry`, `AiRetryConfiguration`). Other `4xx` answers fail at once.
- **Timeouts.** 20 s to connect and 10 min to read (`hestia.llm.*`, `LlmHttpTimeoutConfig`).
  Without a read timeout a dead connection once held a run for three and a half hours. The
  bookmark judge runs inside the upload request and gives up after 30 s; the document is then
  split along its bookmarks.
- **Parallelism.** 16 unit calls and 8 page-description calls at a time
  (`extraction.parallelism`, `figures.parallelism`), chosen against the gateway's burst limit.
- **Lenient parsing.** `LenientJson` accepts what models get wrong in otherwise usable replies:
  LaTeX backslashes inside strings, a key repeated in one object, an invented enum value (read as
  null), and a single object where a list was asked for. Each of these once failed a whole session.
- **Validation.** Replies are checked before anything is stored. An outcome that fails the checks
  is dropped and counted in `extraction_run.dropped_outcomes`, which the review shows. A run that
  ends with no goals fails instead of reporting success. If any session's calls fail after all
  retries, the run fails before storing anything, names those sessions in the progress view, and
  keeps the previous extraction.

## Comparing runs

Each extraction run stores its model, prompt version and parameters in `extraction_run`
(`model`, `prompt_version`, `params`), and each page description stores its model and prompt
version in `page_description`. The extraction prompt (`SessionExtractionService.PROMPT_VERSION`)
and the figure prompt (`PageDescriptionService.FIGURE_PROMPT_VERSION`) carry versions; bump them
when the prompt changes. The other prompts have no version.

`LlmUsageAdvisor` counts the tokens of every answered call. During an extraction the counts are
booked to the run's current phase and logged when the run ends, so the cost of a run is measured.
Failed or timed-out calls report no usage.

## Tests

- No automated test calls a model. Server tests mock `LlmClient` or the `ChatClient.Builder`.
- The browser tests run the real server against `client/e2e/fake-llm.mjs`, a stand-in gateway
  that recognises each prompt by the JSON shape it asks for and answers deterministically. A new
  prompt shape needs a matching answer there.
- `*LiveIT` classes call the real gateway (`PageDescriptionModelLiveIT`, `TaxonomyVerbLiveIT`).
  They run only with `RUN_LIVE_LOGOS=1` and a `LOGOS_API_KEY`; each class's Javadoc gives its
  command.
