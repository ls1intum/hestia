# LearningGoalHub

LearningGoalHub reads a course's lecture slides and exercise sheets and extracts learning goals
from them. Instructors review the result as a Topic tree (Topic → Skill → Sub-skill → Knowledge)
in which goals carry Bloom and SOLO levels and point back to the slide they came from. The
sibling Hestia apps (ExamLens, Workshopper) read the goals through a REST API.

| Part | What it is | Docs |
| --- | --- | --- |
| `server/` | Spring Boot 3.5 + Spring AI server, PostgreSQL, Flyway | [server/README.md](server/README.md) |
| `client/` | React 18 + TypeScript + Tailwind CSS, built with Vite | [client/README.md](client/README.md) |
| `docs/ARCHITECTURE.md` | containers, client, data model, legacy values | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| `docs/LLM.md` | every model call, failure handling, comparing runs | [docs/LLM.md](docs/LLM.md) |
| `docs/SECURITY.md` | what is protected, what is open, and what changes with login | [docs/SECURITY.md](docs/SECURITY.md) |
| `docs/api.md` | API contract for the consuming apps | [docs/api.md](docs/api.md) |
| `docs/EXTENDING.md` | checklists for changes that cross server and client | [docs/EXTENDING.md](docs/EXTENDING.md) |
| `docs/acceptance.md` | manual acceptance passes against the requirements | [docs/acceptance.md](docs/acceptance.md) |
| `DEPLOY.md` | containers, CI/CD, VM setup, backups | [DEPLOY.md](DEPLOY.md) |
| `AGENTS.md` | instructions for AI coding agents | [AGENTS.md](AGENTS.md) |

## How it works

1. **Upload.** An instructor creates a course and uploads PDFs, optionally marked as lecture or exercise.
   The server parses the text with Apache Tika and splits each PDF into sessions along its
   bookmarks. Bookmark-less PDFs become one session each.
2. **Extract.** `POST /api/courses/{id}/extract` starts the pipeline in the background. Long
   sessions are cut into page-aligned units of at most 12,000 characters. One LLM call per unit
   returns skills with their knowledge items, a Bloom and SOLO level and a quote for each.
   Text-poor slide pages can be described by a vision model first.
3. **Build the Topic tree.** A planner model names the course's topics, assigns the extracted skills
   to them and groups them under each topic. The tree is stored as `CONTRIBUTES_TO` relationships
   between goals.
4. **Review.** The client shows the tree and a table, with the source PDF page and the highlighted
   quote next to each goal. Instructors edit, delete, add or approve goals there, or add a topic
   with "Find in the slides".
5. **Consume.** ExamLens posts exam tasks and gets goals back. Workshopper reads the goals per
   session. See [docs/api.md](docs/api.md).

All model calls go to the TUM AET Logos gateway (OpenAI-compatible). Defaults are
`openai/gpt-oss-120b` for text and `Qwen/Qwen3.8-27B` for vision, set in
[`libs/shared-llm`](../../libs/shared-llm/README.md). Workshopper uses that library too.

## Prerequisites

- Java 21 (the Gradle wrapper fetches everything else)
- Node.js 22 and npm
- Docker: Postgres for local runs, and Testcontainers for the server tests
- A Logos API key, but only for calls that reach a model (extraction, upload, exam goals). The
  server starts with any non-empty value.

## Run locally

All commands below run from the repository root unless a `cd` says otherwise.

```bash
# 1. Database (pgvector/pgvector:pg16 on localhost:5432)
cp apps/learninggoalhub/.env.example apps/learninggoalhub/.env   # then fill in LOGOS_API_KEY
docker compose -f apps/learninggoalhub/compose.yaml up -d postgres

# 2. A throwaway SAML key pair, kept outside the repository (see below)
mkdir -p ~/.learninggoalhub-dev
openssl req -x509 -newkey rsa:2048 -nodes -days 365 -subj "/CN=localhost" \
  -keyout ~/.learninggoalhub-dev/saml-key.pem -out ~/.learninggoalhub-dev/saml-cert.crt

# 3. Server on http://localhost:8080
LOGOS_API_KEY=<your key> \
SAML_PRIVATE_KEY_LOCATION=file:$HOME/.learninggoalhub-dev/saml-key.pem \
SAML_CERTIFICATE_LOCATION=file:$HOME/.learninggoalhub-dev/saml-cert.crt \
  ./gradlew :apps:learninggoalhub:server:bootRun

# 4. Client on http://localhost:5173/learninggoalhub/
cd apps/learninggoalhub/client && npm install && npm run dev
```

The server reads plain environment variables, not the `.env` file. Export the values you changed
or pass them on the command line as above.

**Why the SAML pair.** The server is configured as a SAML service provider for TUM login, and Spring
refuses to start without a signing key, which no clone contains. Any self-signed pair works locally.
`/api/**` and Swagger UI (`/swagger-ui.html`) need a TUM login, which only works on production. Run the
server with `SPRING_PROFILES_ACTIVE=local` to sign every request in as a dev user (`DevAuthFilter`).
The OpenAPI document at `/v3/api-docs` is public.

**Database migrations.** Flyway applies new migrations on startup, so running a newer branch against
your local database upgrades it, and the upgrade cannot be undone. To try a branch without touching
your data, start a second Postgres on another port and point the server at it with
`POSTGRES_PORT=<port>`.

## Test and check

```bash
./gradlew :apps:learninggoalhub:server:test            # server: JUnit + Testcontainers (Docker must be running)
./gradlew :apps:learninggoalhub:server:test --rerun-tasks   # force a real run; a run that ends in seconds came from the build cache

cd apps/learninggoalhub/client
npm run lint && npm run typecheck && npm test         # client: ESLint, tsc, Vitest
npm run e2e                                           # browser tests; setup in client/README.md
```

CI runs the same checks on every pull request (`.github/workflows/learninggoalhub-cicd.yml`).
The automated tests never call a model. A few `*LiveIT` tests do; they only run when
`RUN_LIVE_LOGOS=1` and `LOGOS_API_KEY` are set, and their Javadoc gives the exact command.

If the server tests hang with zero results, Docker is usually not answering: check that `docker ps`
returns.

## Configuration

Settings live in `server/src/main/resources/application.yml`, each with a comment on why it has
its value. The ones you are most likely to change:

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `LOGOS_API_KEY` | empty | Logos gateway key |
| `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `localhost` / `5432` / `learninggoalhub` ×3 | database |
| `SAML_PRIVATE_KEY_LOCATION` / `SAML_CERTIFICATE_LOCATION` | `classpath:certs/…` (absent) | SAML signing pair |
| `API_KEY_EXAMLENSE` / `API_KEY_WORKSHOPPER` | empty | keys the consuming servers send in `X-Api-Key` |
| `ENFORCE_SERVICE_KEYS` | `false` (`true` in `compose.prod.yaml`) | reject service-only calls without a key |
| `SPRING_PROFILES_ACTIVE` | empty (deploy: `Staging`/`Production`) | `local` (and `Staging`, a group for it) turns off the TUM login |
| `VITE_API_TARGET` (client dev server) | `http://localhost:8080` | where Vite proxies `/learninggoalhub/api` |

Pipeline settings (parallelism, unit size, models, temperatures) are under `hestia.*` in
`application.yml` and can be overridden the usual Spring way, for example
`HESTIA_EXTRACTION_PARALLELISM=4`.
