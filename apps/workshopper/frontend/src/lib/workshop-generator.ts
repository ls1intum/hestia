// ── Types shared across all Workshopper components ─────────────────

export type SessionType =
  | "workshop"
  | "lecture"
  | "exercise"
  | "seminar"
  | "practical"
  | "other";

export type InteractionLevel = "minimal" | "moderate" | "high";

/** Form payload sent to the backend */
export interface WorkshopInput {
  title?: string;
  learningGoals?: string[];
  duration: number;
  participants: number;
  sessionType: SessionType;
  sessionTypeOther?: string;
  studentBackground?: string;
  prerequisites?: string;
  sourceDocument?: string;
  interactionLevel?: InteractionLevel;
  selectedActivities?: string[];
  availableMaterials?: string[];
  uploadedMaterialsText?: string;
}

/** One learning goal with suggested activities (Plan Review step) */
export interface LearningGoalPlan {
  id: string;
  originalGoal?: string;
  goal: string;
  prerequisites: string[];
  achieveActivities: string[];
  assessActivities: string[];
  /** Priority rating 0–5 (0 = no priority set, 5 = highest priority) */
  priority?: number;
}

export interface ActivitySection {
  title: string;
  duration?: number;
  steps?: string[];
  methods: string[];
  materials: string[];
}

/** One timetable block (Result step) */
export interface ActivityBlock {
  blockId?: string;
  phase: string;
  phaseLabel: string;
  goalTag?: string;
  objective: string;
  description: string;
  methods: string[];
  materials: string[];
  sections?: ActivitySection[];
  duration: number;
}

/** The full generated session returned after step 2 */
export interface WorkshopSession {
  id?: string;
  title?: string;
  learningGoal: string;
  studentBackground?: string;
  prerequisites?: string;
  blocks: ActivityBlock[];
  /** Goals omitted by the LLM because there was not enough time to cover them. */
  omittedGoals?: string[];
  slides?: Record<number, any[]>;
}

export interface SkeletonSection {
  title: string;
  duration: number;
}

export interface SkeletonBlock {
  phase: string;
  title?: string;
  description?: string;
  lgIndex: number;
  duration: number;
  sections?: SkeletonSection[];
}

export interface SessionSkeleton {
  learningGoal: string;
  blocks: SkeletonBlock[];
  omittedGoalIndices: number[];
  /** Optional: existing draft session ID to update on finalisation */
  sessionId?: string;
}

/** Request body for POST /api/workshop/session */
export interface GenerateSessionRequest {
  goals: LearningGoalPlan[];
  meta: WorkshopInput;
  skeleton: SessionSkeleton;
}

/** Lightweight summary returned by GET /api/workshop/sessions (dashboard list) */
export interface SessionSummary {
  id: string;
  title: string;
  learningGoal?: string;
  status: "draft" | "complete";
  currentStep?: string;
  type?: "SESSION" | "LECTURE";
  lectureId?: string;
  /** May be an ISO string or a Java-serialized array [year, month, day, hour, minute, second] */
  createdAt: unknown;
  updatedAt: unknown;
}

/** Full detail returned by GET /api/workshop/sessions/{id} */
export interface SessionDetail {
  id: string;
  title: string;
  status: "draft" | "complete";
  currentStep?: string;
  type?: "SESSION" | "LECTURE";
  lectureId?: string;
  draftStateJson?: string;
  session?: WorkshopSession;
}

/**
 * All in-progress form state, serialized to draftStateJson and stored in the DB.
 * Each field is optional since the user may not have reached that step yet.
 */
export interface DraftState {
  workshopInput?: Partial<WorkshopInput>;
  refinedGoals?: LearningGoalPlan[];
  skeleton?: SessionSkeleton;
  session?: WorkshopSession;
  completedTasks?: string[];
}
