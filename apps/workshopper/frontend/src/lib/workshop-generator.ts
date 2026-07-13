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
  lgIndex?: number;
}

export interface SlideData {
  title: string;
  subtitle?: string;
  bullets: string[];
  notes?: string;
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
  slides?: Record<number, SlideData[]>;
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

export function stripLgPrefix(text: string): string {
  return text.replace(/^\s*LG\s*\d+\s*[:.\-]\s*/i, "").trim();
}

export function generateDefaultSkeleton(goals: LearningGoalPlan[], totalDuration: number): SessionSkeleton {
  const arriveTime = 5;
  const activateTime = Math.max(5, Math.round((totalDuration / 90) * 5));
  // Break: 5 min for 90-min session (was 10 min — too much overhead)
  const breakTime = totalDuration >= 90 ? Math.round((totalDuration / 90) * 5) : 0;
  // Buffer: 5 min for 90-min session
  const bufferTime = Math.round((totalDuration / 90) * 5);
  const summaryTime = 10;
  const globalEvaluateTime = 10;

  const fixedTime = arriveTime + activateTime + breakTime + bufferTime + summaryTime + globalEvaluateTime;
  const remaining = Math.max(0, totalDuration - fixedTime);
  const baseTimePerGoal = goals.length > 0
    ? Math.max(10, Math.floor(remaining / goals.length / 5) * 5)
    : 0;

  // Distribute leftover minutes into the learning cycle blocks (last one gets the remainder)
  const allocatedToGoals = baseTimePerGoal * goals.length;
  const leftover = Math.max(0, remaining - allocatedToGoals);

  const blocks: SkeletonBlock[] = [
    { phase: "ARRIVE", title: "Arrive & Welcome", duration: arriveTime, lgIndex: 0 },
    { phase: "ACTIVATE", title: "Activate Knowledge", duration: activateTime, lgIndex: 0 },
  ];

  goals.forEach((g, i) => {
    // Give extra leftover minutes to the last learning cycle
    const duration = i === goals.length - 1 ? baseTimePerGoal + leftover : baseTimePerGoal;
    const informTime = Math.max(5, Math.floor(duration / 2));
    const processTime = Math.max(5, duration - informTime);

    blocks.push({
      phase: "LEARNING_CYCLE",
      title: stripLgPrefix(g.goal),
      duration,
      lgIndex: i + 1,
      sections: [
        { title: "You explain", duration: informTime },
        { title: "Participants Practice", duration: processTime },
      ],
    });

    if (i === Math.floor(goals.length / 2) - 1 && breakTime > 0) {
      blocks.push({ phase: "BREAK", title: "Break", duration: breakTime, lgIndex: 0 });
    }
  });

  blocks.push({ phase: "EVALUATE", title: "Check Understanding", duration: globalEvaluateTime, lgIndex: 0 });
  blocks.push({ phase: "SUMMARY", title: "Summary & Wrap-up", duration: summaryTime, lgIndex: 0 });
  if (bufferTime > 0) {
    blocks.push({ phase: "BUFFER", title: "Buffer", duration: bufferTime, lgIndex: 0 });
  }

  return {
    learningGoal: "Combined Session Goals",
    blocks,
    omittedGoalIndices: [],
  };
}
