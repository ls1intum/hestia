# LearningGoalHub API (v1)

REST API for consuming the learning goals extracted by LearningGoalHub. Written for the
sibling thesis projects that integrate against this service.

- Base URL — the LearningGoalHub server. Reachable only on the **LRZ VPN**:
  - deployed (test): `https://hestia-test.aet.cit.tum.de/learninggoalhub`
  - local dev: `http://localhost:8080`
- The deployed service runs over HTTPS (trusted RBG/GÉANT certificate) behind a shared
  reverse proxy, under the `/learninggoalhub` path prefix. The proxy strips the prefix before
  the server sees it, so every endpoint below is reached at `<base>/api/...` — e.g.
  `https://hestia-test.aet.cit.tum.de/learninggoalhub/api/courses`.
- The machine-readable contract lives at `<base>/v3/api-docs` (OpenAPI 3, also browsable via
  `<base>/swagger-ui.html`); a checked-in snapshot is at `client/openapi/openapi.json`.
- No authentication yet — the API is currently consumed inside the trusted dev/university setup.
- All endpoints speak JSON unless noted. Breaking changes are treated as expensive
  (see `AGENTS.md`); additions are favoured over shape changes.

## Endpoints for consumers

### `GET /api/courses`

Paginated course list. Standard Spring pageable params (`page`, `size`, `sort`).

### `GET /api/courses/{courseId}/learning-goals`

Paginated flat list of a course's learning goals.

| Query param | Meaning |
| --- | --- |
| `status` | optional filter: `PENDING` or `APPROVED`. Omit for all goals. |
| `page`, `size`, `sort` | standard pagination; defaults `size=50`, `sort=id,asc`. |

Consumers that only want instructor-curated goals should pass `status=APPROVED`.

Paginated responses use the stable `PagedModel` shape:

```json
{
  "content": [ { ...LearningGoalResponse... } ],
  "page": { "size": 50, "number": 0, "totalElements": 92, "totalPages": 2 }
}
```

### `GET /api/courses/{courseId}/learning-goals/by-session`

The same goals grouped by the hierarchy node (module / session / exercise / exam root) they belong to —
use this when you need per-session granularity instead of a flat list. Not paginated.

| Query param | Meaning |
| --- | --- |
| `status` | optional filter: `PENDING` or `APPROVED`. Omit for all goals. |
| `nodeId` | optional: narrow to the goals of a single hierarchy node (one session/module/exercise). Returns at most one group; an unknown id yields an empty list. |

The `nodeId` values come from this same endpoint: call it **without** `nodeId` first and read the
`nodeId` of the group you want, then pass that id to fetch only that group.

```json
[
  {
    "nodeId": 17,
    "level": "MODULE",
    "label": "Introduction to Machine Learning",
    "goals": [ { ...LearningGoalResponse... } ]
  },
  {
    "nodeId": 18,
    "level": "SESSION",
    "label": "Chapter 01: ML Basics",
    "goals": [ ... ]
  }
]
```

- Groups follow node creation order: the module root first, then sessions/exercises in
  document order.
- Goals without a hierarchy node (rare) come last in a group whose `nodeId`, `level` and
  `label` are `null`.
- Nodes without any (matching) goals are omitted, so with `status=APPROVED` a session
  disappears once none of its goals are approved.

### `GET /api/courses/{courseId}/learning-goals/export.csv`

The same data as a CSV download (one row per goal), for spreadsheet workflows.

### `POST /api/courses/{courseId}/exam-tasks/learning-goals`

Derives and persists learning goals for the tasks of an exam (built for ExamLens). Send the
exam as an ordered list of blocks; each block is either shared `CONTEXT` or a `TASK`:

```json
{
  "blocks": [
    { "blockId": "1", "blockType": "context", "taskType": null,
      "description": "The following tasks refer to the lecture's ML chapter." },
    { "blockId": "2", "blockType": "task", "taskType": "singleChoice",
      "description": "What is 1 + 1?" },
    { "blockId": "3", "blockType": "task", "taskType": "freeText",
      "description": "Explain how LLMs have shaped the way humans work." }
  ]
}
```

- `blockType` — `context` or `task` (case-insensitive). A context block applies to every task
  **after** it, so a task is generated with all preceding context blocks attached.
- `taskType` — free-form label (e.g. `singleChoice`, `freeText`); passed to the LLM as a hint,
  may be `null`.
- One LLM call per task block, so the request is synchronous and takes a few seconds per task.
- The generated goals are persisted with origin `EXAM` under the course's `EXAM` hierarchy
  root (created on first use) and review status `PENDING`. Posting the same exam twice creates
  the goals twice — there is no deduplication yet.

The response echoes each **task** block's `blockId` (context blocks yield no entry) with the
goals created for it, in the same `LearningGoalResponse` shape as the other endpoints
(`sources` and `relationships` are empty for exam goals; `hierarchy` is all-`null` because the
`EXAM` root is outside the module → session → exercise path):

```json
[
  { "blockId": "2", "goals": [ { "id": 512, "text": "Recall basic integer addition.", ... } ] },
  { "blockId": "3", "goals": [ { "id": 513, "text": "..." }, { "id": 514, "text": "..." } ] }
]
```

An optional `model` query param overrides the SAIA chat model for the generation calls.
Validation errors (no blocks, no task block, blank task description) return `400` in the
standard error shape below.

Exam goals also show up in the read endpoints above: in the flat list, and in `by-session` as
a group whose `level` is `EXAM` and whose `label` is `Exam`.

## `LearningGoalResponse`

```json
{
  "id": 421,
  "text": "Explain the bias-variance tradeoff.",
  "kind": "EXPLICIT",
  "status": "APPROVED",
  "hierarchy": { "module": "Introduction to Machine Learning", "session": "Chapter 07: Evaluation", "exercise": null },
  "bloomLevel": "UNDERSTAND",
  "soloLevel": "RELATIONAL",
  "createdAt": "2026-06-04T18:21:09.123456+02:00",
  "sources": [ { "documentId": 68, "filename": "Chapters 1-10.pdf", "snippet": "…bias-variance…" } ],
  "relationships": [
    { "type": "CONTRIBUTES_TO", "targetGoalId": 430, "targetText": "…", "confidence": 1.0, "origin": "HIERARCHY" }
  ]
}
```

Field semantics:

- `kind` — `EXPLICIT`: stated as a learning goal in the material; `IMPLICIT`: inferred from
  the content.
- `status` — review lifecycle: extraction produces `PENDING`; an instructor approves to
  `APPROVED`. Rejected goals are deleted, so there is no rejected state.
- `hierarchy` — flattened module → session → exercise labels of the goal's node; `null` when
  the goal is not attached to the hierarchy. Levels missing from the path are `null`.
- `bloomLevel` (`REMEMBER` … `CREATE`) and `soloLevel` (`PRESTRUCTURAL` … `EXTENDED_ABSTRACT`)
  — taxonomy classification; `null` when not (yet) classified.
- `sources` — document provenance with a text snippet per supporting document.
- `relationships` — outgoing edges to other goals: `CONTRIBUTES_TO` (a goal feeds the goal
  above it in the competency tree). `PREREQUISITE_OF` and `OVERLAPS_WITH` are legacy types
  that may still appear on courses extracted before mid-2026; new extractions no longer
  produce them. `origin` says how the edge was found (`HIERARCHY`, `EMBEDDING`, `LLM`);
  `confidence` is in `[0, 1]`.

## Errors

Error responses (4xx/5xx) use a stable JSON shape with a machine-readable `code` and a
human-readable `message`:

```json
{ "code": "NOT_FOUND", "message": "Course not found: 999999" }
```

- `code` is the HTTP status name (e.g. `NOT_FOUND`, `BAD_REQUEST`, `INTERNAL_SERVER_ERROR`); branch
  on it rather than parsing `message`.
- The HTTP status line carries the same status, so consumers can also key on that.

## Consuming from Java (Spring Boot)

There is no need to handle raw JSON: declare small records that mirror the parts of the
contract you use, and Jackson (part of Spring Boot's web starter) binds requests and
responses automatically. Declare only the fields you need — unknown JSON properties are
ignored.

The snippets below are a complete, copy-paste client for the exam-task endpoint. First the
DTOs:

```java
public record ExamBlock(String blockId, String blockType, String taskType, String description) {}

public record GenerateExamGoalsRequest(List<ExamBlock> blocks) {}

@JsonIgnoreProperties(ignoreUnknown = true)
public record ExamTaskGoals(String blockId, List<Goal> goals) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Goal(long id, String text, String bloomLevel, String soloLevel) {}
}
```

Then a service that owns the HTTP call (`learninggoalhub.base-url` in your
`application.yml`, e.g. the deployed base URL from the top of this document):

```java
@Service
public class LearningGoalHubClient {

    private final RestClient restClient;

    public LearningGoalHubClient(@Value("${learninggoalhub.base-url}") String baseUrl) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        // Goal generation runs one LLM call per task block, so a full exam takes a while.
        requestFactory.setReadTimeout(Duration.ofMinutes(5));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public List<ExamTaskGoals> generateExamGoals(long courseId, List<ExamBlock> blocks) {
        return restClient.post()
                .uri("/api/courses/{courseId}/exam-tasks/learning-goals", courseId)
                .body(new GenerateExamGoalsRequest(blocks))
                .retrieve()
                .body(new ParameterizedTypeReference<List<ExamTaskGoals>>() {});
    }
}
```

Callers then work with plain Java objects (`result.get(0).goals().get(0).text()`); no JSON
appears anywhere in consumer code. The read endpoints work the same way — mirror the
response shapes shown above (for paginated endpoints, wrap your record in a
`content`/`page` record matching the `PagedModel` shape).

If your project ends up using many endpoints, you can instead generate a full typed client
from the live OpenAPI spec at `<base>/v3/api-docs` with
[openapi-generator](https://openapi-generator.tech) (generator `java`, library
`restclient`). For one or two endpoints, the hand-written records above are simpler and
under your control.

## Endpoints not meant for consumers

`PATCH`/`DELETE /api/courses/{courseId}/learning-goals/{goalId}` (instructor review),
`POST /api/courses/{courseId}/documents` (upload) and `POST /api/courses/{courseId}/extract`
(pipeline trigger) back the LearningGoalHub UI. They are not access-restricted yet, but
consumer projects should treat them as off-limits.
