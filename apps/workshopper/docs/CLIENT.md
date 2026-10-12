# Client Architecture & Components

The Workshopper client is a single-page app built with React 18, Vite and TypeScript, styled with Tailwind CSS and `shadcn/ui` (Radix) components. It is served under the base path `/workshopper/` (`vite.config.ts`), so locally at http://localhost:5173/workshopper/.

## Key Directories

```
client/src/
├── App.tsx            # layout: header, step bar, renders the current step, confirmation dialogs
├── hooks/
│   ├── useWizardState.ts      # all wizard state, step order, draft saving, leave guard
│   ├── useTimetableEditing.ts # timetable blocks: inline edits, edit-mode drafts, add/delete/reorder, saving
│   ├── useBlockRegeneration.ts  # timetable changes that regenerate a block with the LLM
│   ├── useSlideDeck.ts        # slide deck: generate, download, template upload/remove
│   └── use-toast.ts
├── lib/
│   ├── api.ts             # every call to the server (components never call fetch), 401/403 handling
│   ├── workshop-generator.ts  # shared types (WorkshopInput, LearningGoalPlan, WorkshopSession,
│   │                          #   SlideData, DraftState, …) and generateDefaultSkeleton
│   ├── activity-taxonomy.ts   # Bloom's/SOLO → activity (mirror of the server's ActivityRecommender)
│   ├── phases.ts              # the block phases: label, emoji, colour, templates for added blocks
│   ├── block-content.ts       # what an expanded block shows (prompt, content, takeaways) from its steps
│   ├── block-edits.ts         # pure edits of one block (step text and minutes, add/delete step)
│   ├── constants.ts           # activity catalogue, colours
│   ├── learning-goal-hub.ts   # client for importing goals from the Learning Goal Hub
│   └── utils.ts
├── components/
│   ├── SessionsDashboard.tsx  # start page: courses, sessions, drag and drop, exports
│   ├── WorkshopFormStep1/2/2b.tsx   # Setup, Activities, Materials
│   ├── CourseTemplateStep.tsx # course step 4: slide template
│   ├── CourseSummary.tsx      # Review step of a session inside a course
│   ├── WorkshopGoalEntry.tsx  # Goals (+ LGHImportModal)
│   ├── WorkshopGeneratedTimetable.tsx  # Timetable (layout; logic in the timetable hooks)
│   ├── timetable/             # block row, block details (shared with Final Review), inline editors,
│   │                          #   understanding check, evaluate mapping panel
│   ├── SlideWorkstation.tsx   # Preparation: slides, prep tasks per slide, template, download
│   ├── slides/                # slide preview, slide editor form, prep tasks, navigation
│   ├── WorkshopFinalReview.tsx  # Final Review and export
│   ├── TemplateUploadBox.tsx    # the PowerPoint template box (used in three places)
│   └── ui/                      # shadcn/ui components
└── assets/logos/              # Hestia wordmarks
```

## State Management

There is no external store. `useWizardState.ts` holds the wizard's state in React state and returns it, with the step handlers, to `App.tsx`:

* **Entity type:** `SESSION` or `COURSE`. The steps of each are defined there (`ALL_STEPS`, `COURSE_STEPS`, `computeStepOrder`).
* **Step state:** the current step and the furthest reachable step.
* **The data being edited:**
  * `workshopInput`: the settings;
  * `refinedGoals` and `keptGoals`;
  * `currentSkeleton`, `session` (the timetable) and `slidesCache`;
  * `templateInfo`.
* **Saving:**
  * **`persistDraft`** saves the whole `DraftState` to the server, debounced. A save that is still waiting is sent immediately on navigation (`flushDraft`).
  * **`resumeSession`** reloads a session or course and decides which step it opens at.
  * **Reload:** the current page (dashboard, or the session/course and its step) is kept in the tab's `sessionStorage` (`workshopper_location`), so reloading returns to it.

**Leave guard:** a step with unsaved input registers a `LeaveGuard` (`isDirty`, `confirmLeave`) in `leaveGuardRef`. The step bar, Back/Next, the dashboard arrow, the browser's back button and page reload all go through `confirmLeave`, so the step can offer to save, keep, discard or stay. The timetable, the slide workstation, the Goals step and the settings forms each register one.

## Wizard Flow

**Session:**
1. **Setup** (`WorkshopFormStep1`): title, duration, participants, session type, student background.
2. **Activities** (`WorkshopFormStep2`): the activity types to use.
3. **Materials** (`WorkshopFormStep2b`): the materials available.
4. **Review** (`CourseSummary`): only for a session inside a course; shows the inherited settings and slide template.
5. **Goals** (`WorkshopGoalEntry`): enter goals or import them from the Learning Goal Hub (`LGHImportModal`).
   * Each goal can be checked by AI (`/refine-goal`).
   * Continue fixes the grammar (`/fix-goals-grammar`), builds the skeleton and generates the timetable.
6. **Timetable** (`WorkshopGeneratedTimetable` + `timetable/SortableBlockRow`):
   * reorder blocks (dnd-kit), add blocks from a template, delete blocks;
   * switch or add activities (each regenerates the block);
   * edit a block in edit mode, which is a draft saved with ✓;
   * map evaluation activities to goals (`EvaluateMappingPanel`).
7. **Preparation** (`SlideWorkstation`, rendered by `App.tsx`): generate slides per block, edit them, tick off the prep tasks of each slide (saved with the session), upload a template, download the deck.
8. **Final Review** (`WorkshopFinalReview`): overview, PDF and PPTX export.

**Course:** Setup, Activities, Materials, **Template** (`CourseTemplateStep`). Sessions created in a course start from its settings.

## UI Library
* **Tailwind CSS:** layout and styling; the colour tokens are CSS variables (`--hestia-*`) in `index.css`.
* **shadcn/ui:** accessible primitives (dialogs, dropdowns, tooltips, …) in `components/ui`.
* **dnd-kit:** drag and drop of timetable blocks (`WorkshopGeneratedTimetable`, `SortableBlockRow`). The dashboard and the evaluate mapping panel use native HTML drag and drop.

## Checks

From `apps/workshopper/client`:

```bash
npx tsc --noEmit
```

```bash
npm run build
```

`npx tsc --noEmit` type-checks the client; `npm run build` type-checks and builds it.
