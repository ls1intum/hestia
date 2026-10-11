# Backend Architecture & Database Schema

*Note: For a detailed explanation of the UseCase migration, strict boundary enforcement, and security architecture, please read [ARCHITECTURE.md](./ARCHITECTURE.md).*

The Workshopper backend is a Spring Boot 3 application (Java 21) that provides REST APIs for the frontend, manages the AI generation pipeline using Spring AI, and handles document generation (PDF/PPTX).

## Key Components

```text
backend/src/main/java/com/workshopper/
├── controller/         # REST Controllers (Boundary layer)
├── facade/             # WorkshopSessionFacade (Orchestration & Auth checks)
├── usecase/            # Isolated business logic (e.g., GenerateTimetableUseCase)
├── service/            # CRUD and external integrations (WorkshopService, PdfExport)
├── model/              # JPA Entities (WorkshopSessionEntity)
├── repository/         # Spring Data JPA interfaces
└── dto/                # Data Transfer Objects (Records)
```

## Database Schema

The application uses PostgreSQL. The primary entity is `WorkshopSessionEntity`.

### `workshop_sessions` Table
- `id` (String/UUID): Primary key.
- `title` (String): The title of the session.
- `owner_id` (String): The SAML ID (or `dev-local-user`) of the session creator.
- `lecture_id` (String): Optional group ID to associate multiple sessions with a single lecture series.
- `sort_order` (Integer): Order index for UI display.
- `status` (String): Enum indicating if the session is `draft` or `complete`.
- `input_json` (JSONB): The initial metadata provided by the user (target audience, context, etc.).
- `goals_json` (JSONB): The reviewed and finalized list of learning goals.
- `session_json` (JSONB): The finalized timetable and session blocks.
- `slides_json` (JSONB): Cached JSON representation of slides for PPTX generation.
- `template_blob` (Bytea): Custom uploaded PPTX template file.

All complex JSON objects (DTOS) are stored as JSONB columns to allow flexible iteration without constant schema migrations.
