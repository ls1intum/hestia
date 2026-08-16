# Frontend Architecture & Components

The Workshopper frontend is a Single Page Application (SPA) built with React 18 and Vite. It is written in TypeScript and styled using Tailwind CSS and the `shadcn/ui` component library.

## Key Directories

```
frontend/src/
├── assets/         # Static assets (images, fonts)
├── components/     # React components
│   ├── ui/         # Reusable UI components (shadcn/ui)
│   └── timetable/  # Complex drag-and-drop timetable components
├── hooks/          # Custom React hooks (e.g., useDebounce, API hooks)
├── lib/            # Utility functions, API clients, and types
│   ├── api.ts      # API fetch client
│   └── types.ts    # TypeScript interfaces for API dtos
└── App.tsx         # Main application routing and state
```

## State Management

The application state is primarily managed in `App.tsx` using standard React `useState` and passed down to child components. The main state objects represent the data collected at each step of the workshop creation process:
- `WorkshopInput` (Context, audience, topic)
- `LearningGoalPlan[]` (Draft learning goals)
- `WorkshopSession` (Final timetable and blocks)

## Workshop Generation Flow (Steps)

The UI guides the user through a linear generation flow:

1. **Step 1 (Input)**: `WorkshopFormStep1` collects basic metadata and optional PDF material uploads.
2. **Step 2 (Activities)**: `WorkshopFormStep2` allows the user to select preferred teaching structures.
3. **Step 2b (Materials)**: `WorkshopFormStep2b` allows the user to select physical materials (whiteboards, sticky notes, etc.).
4. **Step 3 (Goals Review)**: `WorkshopFormStep3` displays the AI-generated learning goals for the user to tweak, reorder, or refine.
5. **Step 4 (Timetable)**: `WorkshopFormStep4` displays the fully generated timetable. Users can drag and drop blocks, adjust timings, and customize individual activities using the `SortableBlockRow` component.
6. **Export**: The user can then export the session to a generated PDF or PPTX slide deck.

## UI Components & Styling

- **Tailwind CSS**: Utility-first CSS framework for styling.
- **shadcn/ui**: Accessible and customizable UI components built on Radix UI.
- **dnd-kit**: Used in the timetable view for drag-and-drop block reordering.
