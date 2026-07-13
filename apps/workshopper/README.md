# Workshopper — AI Workshop Session Planner

A full-stack prototype for generating structured teaching sessions using AI.

> [!WARNING]
> **Important Security Notice:** This prototype currently has **no authentication layer**. It is designed for VPN-internal thesis environments. It is **not suited for open networks** in its current state. Deploying it publicly will expose your LLM API key and allow unauthenticated usage of the endpoints.

## Stack

| Layer     | Tech                                              |
|-----------|---------------------------------------------------|
| Frontend  | Vite + React 18 + TypeScript + Tailwind CSS       |
| UI        | shadcn/ui (Radix UI primitives)                   |
| Backend   | Spring Boot 3.3 (Java 21)                         |
| Database  | PostgreSQL (with pgvector)                        |
| LLM       | Spring AI (configured via `libs:shared-llm`)      |

---

## Quick Start (Local Development)

### 1. Configure the Environment

Create a `.env` file in the `apps/workshopper/` directory:

```bash
WORKSHOPPER_SAIA_API_KEY="your-api-key-here"
POSTGRES_USER="workshopper"
WORKSHOPPER_POSTGRES_PASSWORD="your-secure-password"
POSTGRES_DB="workshopper"
```

### 2. Start the Database

From the `apps/workshopper` directory, start the local PostgreSQL container:
```bash
docker compose up -d
```

### 3. Start the Backend

From the repository root, start the backend using Gradle:
```bash
WORKSHOPPER_SAIA_API_KEY="your-api-key-here" ./gradlew :apps:workshopper:backend:bootRun
# → Starts on http://localhost:8081
```

### 4. Start the Frontend

```bash
cd apps/workshopper/frontend
npm install
npm run dev
# → Opens http://localhost:5173
```

---

## App Flow

1. **Form** — Enter learning goal, duration, participants, optional PDF upload
2. **Plan Review** — AI generates draft learning goals; you reorder/edit them
3. **Result** — AI generates full session timetable; edit blocks; download as PDF or PPTX

---

## Project Structure

```
workshopper/
├── frontend/           ← Vite React app
│   └── src/
│       ├── components/ ← UI components and workshop flow
│       ├── lib/        ← Types, API client, PDF parser
│       └── hooks/      ← Custom hooks
└── backend/            ← Spring Boot app
    └── src/main/java/com/workshopper/
        ├── controller/ ← REST endpoints
        ├── service/    ← LLM + business logic + Document Export (PDF/PPTX)
        ├── dto/        ← Request/response types
        ├── model/      ← JPA entity
        └── repository/ ← Spring Data JPA
```
