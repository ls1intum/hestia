# Backend Architecture & Database Schema

The Workshopper backend is a Spring Boot 3 application (Java 21) that provides REST APIs for the frontend, manages the AI generation pipeline using Spring AI, and handles document generation (PDF/PPTX).

## Key Components

```
backend/src/main/java/com/workshopper/
├── controller/         # REST Controllers (WorkshopController)
├── service/            # Core business logic and LLM integrations
│   ├── WorkshopService.java    # AI generation pipeline coordination
│   ├── PdfExportService.java   # PDF generation (LaTeX -> PDF)
│   └── PptxExportService.java  # PowerPoint slide generation
├── model/              # JPA Entities
│   └── WorkshopSessionEntity.java
├── repository/         # Spring Data JPA interfaces
└── dto/                # Data Transfer Objects (Records)
```

## Database Schema

The application uses PostgreSQL. The primary entity is the `WorkshopSessionEntity`.

### `workshop_sessions` Table
- `id` (String/UUID): Primary key.
- `title` (String): The title of the session.
- `lecture_id` (String): Optional group ID to associate multiple sessions with a single lecture series.
- `sort_order` (Integer): Order index for UI display.
- `status` (String): Enum indicating if the session is `DRAFT` or `FINISHED`.
- `input_json` (JSONB): The initial metadata provided by the user (target audience, context, etc.).
- `goals_json` (JSONB): The reviewed and finalized list of learning goals.
- `session_json` (JSONB): The finalized timetable and session blocks.
- `slides_json` (JSONB): Cached JSON representation of slides for PPTX generation.
- `template_blob` (Bytea): Custom uploaded PPTX template file.

All complex JSON objects (DTOS) are stored as JSONB columns to allow flexible iteration without constant schema migrations.

## Export Services

1. **PDF Export**: The backend dynamically generates LaTeX markup based on the session timetable and passes it through an LLM to format it into a beautiful document. It then compiles the LaTeX to a PDF binary stream.
2. **PPTX Export**: The backend constructs OpenXML PowerPoint presentations, utilizing Apache POI to inject generated slide content into templated PPTX decks.
