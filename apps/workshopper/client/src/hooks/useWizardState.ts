import { useState, useCallback, useRef, useEffect } from "react";
import {
  generateSession,
  getSessionDetail,
  getCourseDetail,
  saveDraft,
  saveCourseDraft,
  handleAuthError,
  errorMessage,
} from "@/lib/api";
import { toast } from "@/hooks/use-toast";
import type {
  WorkshopInput,
  LearningGoalPlan,
  WorkshopSession,
  SessionSkeleton,
  DraftState,
  TemplateInfo,
  KeptGoal,
} from "@/lib/workshop-generator";

/** The settings edited on the three form steps (Setup, Activities, Materials). */
const FORM_FIELDS = [
  "title", "sessionType", "sessionTypeOther", "duration", "participants", "studentBackground",
  "selectedActivities", "availableMaterials",
] as const satisfies readonly (keyof WorkshopInput)[];

/** Lets a step with unsaved input intercept navigation away from it. */
export interface LeaveGuard {
  /** True while there is input that has not been saved yet. */
  isDirty(): boolean;
  /** Shows the step's save/discard/stay dialog and calls `proceed` once the user chose to leave. */
  confirmLeave(proceed: () => void): void;
}
import { defaultEvaluateMappings } from "@/lib/activity-taxonomy";
import { generateDefaultSkeleton, SlideData, templateInfoFromDetail } from "@/lib/workshop-generator";

export type Step = "input-1" | "input-2" | "input-2b" | "course-template" | "course-summary" | "goals" | "timeline" | "prepare" | "final-review";

const ALL_STEPS: { id: Step; label: string }[] = [
  { id: "input-1",  label: "Setup" },
  { id: "input-2",  label: "Activities" },
  { id: "input-2b", label: "Materials" },
  { id: "course-template", label: "Template" },
  { id: "course-summary", label: "Review" },
  { id: "goals",    label: "Goals" },
  { id: "timeline", label: "Timetable" },
  { id: "prepare",  label: "Preparation" },
  { id: "final-review", label: "Final Review" },
];

/** Steps of creating or editing a course; sessions use the rest. */
const COURSE_STEPS: Step[] = ["input-1", "input-2", "input-2b", "course-template"];

let hasRestored = false;

/**
 * Where the user is in this tab (dashboard, or a session/course and its step), so a reload
 * returns to that page. Kept in sessionStorage: per tab, gone when the tab is closed.
 */
const LOCATION_KEY = "workshopper_location";
type SavedLocation = { id: string; type: "SESSION" | "COURSE"; step: string };

function readLocation(): SavedLocation | null {
  try {
    const raw = sessionStorage.getItem(LOCATION_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function computeStepOrder(entityType: "SESSION" | "COURSE", courseId: string | null): Step[] {
  return ALL_STEPS
    .filter(s => {
      if (entityType === "COURSE") return COURSE_STEPS.includes(s.id);
      if (s.id === "course-template") return false;
      if (!courseId && s.id === "course-summary") return false;
      return true;
    })
    .map(s => s.id);
}

export function useWizardState() {
  const [view, setView] = useState<"dashboard" | "wizard">("dashboard");
  const [step, setStep]         = useState<Step>("input-1");
  const [highestStepIdx, setHighestStepIdx] = useState(0);
  const [darkMode, setDarkMode] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [isFinished, setIsFinished] = useState(false);
  // N-2: confirmation dialog when navigating back from timeline → goals
  const [showTimelineBackConfirm, setShowTimelineBackConfirm] = useState(false);
  const isGeneratingRef = useRef(false);

  // Current session being created/edited
  const [sessionId, setSessionId] = useState<string | null>(null);
  const sessionIdRef = useRef<string | null>(null);
  const setSessionIdSynced = (id: string | null) => {
    sessionIdRef.current = id;
    setSessionId(id);
  };
  const [currentCourseId, setCurrentCourseId] = useState<string | null>(null);
  const [entityType, setEntityType] = useState<"SESSION" | "COURSE">("SESSION");

  const STEPS = ALL_STEPS.filter(s => {
    if (entityType === "COURSE") return COURSE_STEPS.includes(s.id);
    if (s.id === "course-template") return false;
    if (!currentCourseId && s.id === "course-summary") return false;
    return true;
  });
  const STEP_ORDER = STEPS.map((s) => s.id);

  // All form state
  const [workshopInput, setWorkshopInput] = useState<Partial<WorkshopInput>>({});
  const [refinedGoals,  setRefinedGoals]  = useState<LearningGoalPlan[]>([]);
  const [session, setSession] = useState<WorkshopSession | null>(null);
  const [originalSession, setOriginalSession] = useState<WorkshopSession | null>(null);
  const [currentSkeleton, setCurrentSkeleton] = useState<SessionSkeleton | null>(null);
  const [completedTasks, setCompletedTasks] = useState<string[]>([]);
  const [slidesCache, setSlidesCache] = useState<Record<number, SlideData[]>>({});
  const [templateInfo, setTemplateInfo] = useState<TemplateInfo | null>(null);
  const [keptGoals, setKeptGoals] = useState<KeptGoal[] | null>(null);

  // Debounce ref for draft saves
  const draftSaveTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  /** Callers waiting for the debounced draft save to finish (they get the saved id). */
  const pendingResolvers = useRef<Array<(id: string | null) => void>>([]);
  /** The save waiting for its debounce, so navigation can send it right away instead of dropping it. */
  const pendingSave = useRef<(() => Promise<void>) | null>(null);
  /** The step the draft was last saved with, so saves that aren't a step change can keep it. */
  const lastSavedStepRef = useRef<string | null>(null);
  /** The settings (form steps) as last saved, to tell whether the forms have unsaved changes. */
  const savedInputRef = useRef<Partial<WorkshopInput>>({});

  /**
   * Set by a step with unsaved input (the timetable while a block is in edit mode). Anything that
   * leaves the step asks it first, so it can offer to save or discard instead of losing the input.
   */
  const leaveGuardRef = useRef<LeaveGuard | null>(null);

  /** Runs `proceed` right away, or after the current step's unsaved-changes dialog. */
  const confirmLeave = useCallback((proceed: () => void) => {
    const guard = leaveGuardRef.current;
    if (guard && guard.isDirty()) guard.confirmLeave(proceed);
    else proceed();
  }, []);

  /** Shows that a save failed, at most once every few seconds (autosaves can fail in a row). */
  const lastSaveFailureToast = useRef(0);
  const reportSaveFailure = (e: unknown) => {
    console.warn("Draft save failed", e);
    if (Date.now() - lastSaveFailureToast.current < 5000) return;
    lastSaveFailureToast.current = Date.now();
    toast({
      title: "Your changes could not be saved",
      description: errorMessage(e, "Check your connection and try again."),
      variant: "destructive",
    });
  };

  /** Persist current draft state to the server, debounced. Resolves with the session id, or null if saving failed. */
  const persistDraft = useCallback(
    async (
      draft: DraftState,
      currentStep: string,
      currentSessionId: string | null,
      courseId: string | null
    ): Promise<string | null> => {
      if (draftSaveTimeout.current) clearTimeout(draftSaveTimeout.current);
      lastSavedStepRef.current = currentStep;
      if (draft.workshopInput) savedInputRef.current = draft.workshopInput;
      return new Promise((resolve) => {
        pendingResolvers.current.push(resolve);
        const run = async () => {
          draftSaveTimeout.current = null;
          pendingSave.current = null;
          try {
            
            let id: string;
            // The entityType state determines if we're creating/editing a Course or a Session.
            if (entityType === "COURSE") {
              id = await saveCourseDraft(draft, currentSessionId, currentStep);
            } else {
              id = await saveDraft(draft, currentSessionId, currentStep, courseId ?? undefined);
            }

            const resolvers = pendingResolvers.current;
            pendingResolvers.current = [];
            resolvers.forEach(r => r(id));
          } catch (e) {
            // A failed save must not look like a saved one: say so, and give waiting steps null
            if (!handleAuthError(e)) reportSaveFailure(e);
            const resolvers = pendingResolvers.current;
            pendingResolvers.current = [];
            resolvers.forEach(r => r(null));
          }
        };
        pendingSave.current = run;
        draftSaveTimeout.current = setTimeout(run, 300);
      });
    },
    [entityType]
  );

  // ── Unsaved settings on the form steps ──────────────────────────────────
  // The three form steps keep their values in workshopInput while you edit, so moving between
  // them loses nothing. Leaving the forms (to a later step, the dashboard, or by reloading)
  // with values that differ from the saved ones asks first.
  const workshopInputRef = useRef(workshopInput);
  workshopInputRef.current = workshopInput;

  /** The form settings as compared and saved: text trimmed, empty text as unset. */
  const normalizeSettings = (i: Partial<WorkshopInput>): Partial<WorkshopInput> => {
    const n: Record<string, unknown> = { ...i };
    for (const f of FORM_FIELDS) {
      const v = n[f];
      if (typeof v === "string") n[f] = v.trim() || undefined;
    }
    return n as Partial<WorkshopInput>;
  };

  const hasUnsavedSettings = () => {
    const pick = (i: Partial<WorkshopInput>) => FORM_FIELDS.map(f => normalizeSettings(i)[f] ?? null);
    return JSON.stringify(pick(workshopInputRef.current)) !== JSON.stringify(pick(savedInputRef.current));
  };

  /** Saves the form settings without moving on; the session keeps the step it was saved at. */
  /** Resolves false if the settings could not be saved (the error has been shown). */
  const saveSettings = async (): Promise<boolean> => {
    const input = normalizeSettings(workshopInputRef.current);
    if ((input.duration ?? 0) < 10) {
      toast({ title: "Duration too short", description: "A session needs at least 10 minutes.", variant: "destructive" });
      return false;
    }
    setWorkshopInput(input);
    const pending = persistDraft(buildDraft(input), lastSavedStepRef.current ?? step, sessionIdRef.current, currentCourseId);
    await flushDraft();
    const id = await pending;
    if (!id) return false;
    if (!sessionIdRef.current) setSessionIdSynced(id);
    return true;
  };

  /** Puts the form settings back to their saved values. */
  const discardSettings = () => {
    const saved = savedInputRef.current;
    setWorkshopInput(prev => {
      const next: Partial<WorkshopInput> = { ...prev };
      for (const f of FORM_FIELDS) {
        if (saved[f] === undefined) delete next[f];
        else (next as Record<string, unknown>)[f] = saved[f];
      }
      return next;
    });
  };

  /** Keeps goal edits as a draft (or clears it with null) without regenerating the timetable. */
  const keepGoalDraft = (goals: KeptGoal[] | null) => {
    setKeptGoals(goals);
    // Keeping a draft isn't progress: the session keeps the step it was saved at, so its later steps stay open
    persistDraft({ ...buildDraft(), keptGoals: goals ?? undefined }, lastSavedStepRef.current ?? (isFinished ? "finished" : "goals"), sessionIdRef.current, currentCourseId);
  };

  /** Sends a save that is still waiting for its debounce right away (instead of cancelling it). */
  const flushDraft = useCallback(async () => {
    if (draftSaveTimeout.current) {
      clearTimeout(draftSaveTimeout.current);
      draftSaveTimeout.current = null;
    }
    const run = pendingSave.current;
    if (run) await run();
  }, []);

  const buildDraft = useCallback(
    (
      input?: Partial<WorkshopInput>,
      goals?: LearningGoalPlan[],
      skel?: SessionSkeleton,
      sess?: WorkshopSession | null,
      tasks?: string[]
    ): DraftState => ({
      workshopInput: input ?? workshopInput,
      refinedGoals: goals ?? refinedGoals,
      skeleton: skel ?? currentSkeleton ?? undefined,
      session: sess ?? session ?? undefined,
      completedTasks: tasks ?? completedTasks,
      keptGoals: keptGoals ?? undefined,
    }),
    [workshopInput, refinedGoals, currentSkeleton, session, completedTasks, keptGoals]
  );

  // ── Navigation helpers ──────────────────────────────────────────────

  const startNewSession = () => {
    setSessionIdSynced(null);
    setTemplateInfo(null);
    setKeptGoals(null);
    setCurrentCourseId(null);
    setEntityType("SESSION");
    setWorkshopInput({});
    savedInputRef.current = {};
    setRefinedGoals([]);
    setSession(null);
    setCurrentSkeleton(null);
    setCompletedTasks([]);
    setSlidesCache({});
    setIsFinished(false);
    setHighestStepIdx(0);
    setStep("input-1");
    setView("wizard");
  };

  const startNewCourse = () => {
    setSessionIdSynced(null);
    setTemplateInfo(null);
    setKeptGoals(null);
    setCurrentCourseId(null);
    setEntityType("COURSE");
    setWorkshopInput({});
    savedInputRef.current = {};
    setRefinedGoals([]);
    setSession(null);
    setCurrentSkeleton(null);
    setCompletedTasks([]);
    setSlidesCache({});
    setIsFinished(false);
    setHighestStepIdx(0);
    setStep("input-1");
    setView("wizard");
  };

  const startSessionFromCourse = async (courseId: string) => {
    setIsLoading(true);
    try {
      const detail = await getCourseDetail(courseId);
      if (detail.draftStateJson) {
        const draft: DraftState = JSON.parse(detail.draftStateJson);
        if (draft.workshopInput) {
          const inheritedInput = { ...draft.workshopInput };
          delete inheritedInput.title;
          setWorkshopInput(inheritedInput);
          savedInputRef.current = inheritedInput;
        }
      }
      setSessionIdSynced(null);
      setCurrentCourseId(courseId);
      setTemplateInfo(templateInfoFromDetail(detail));
      setKeptGoals(null);
      setEntityType("SESSION");
      setRefinedGoals([]);
      setSession(null);
      setCurrentSkeleton(null);
      setCompletedTasks([]);
      setSlidesCache({});
      setIsFinished(false);
      
      // A new session in a course starts at its Review step; the steps up to it are open
      setHighestStepIdx(computeStepOrder("SESSION", courseId).indexOf("course-summary"));
      setStep("course-summary");
      setView("wizard");
    } catch (e) {
      toast({ title: "Error", description: "Failed to load course settings", variant: "destructive" });
    } finally {
      setIsLoading(false);
    }
  };

  /** Opens a session or course where it was left off; `step` (after a reload) opens that step instead if it is reachable. */
  const resumeSession = async (id: string, opts?: { silent?: boolean; type?: "SESSION" | "COURSE"; step?: string }) => {
    setIsLoading(true);
    try {
      
      const resolvedEntityType = opts?.type ?? "SESSION";
      const detail = resolvedEntityType === "COURSE" ? await getCourseDetail(id) : await getSessionDetail(id);
      // R-2: resolve courseId locally so we can compute the correct step order immediately
      const resolvedCourseId = detail.courseId ?? null;

      // Start from a clean slate: nothing from a previously opened session or course may leak
      // into this one (e.g. a course save picking up the last session's title and timetable)
      setWorkshopInput({});
      savedInputRef.current = {};
      setRefinedGoals([]);
      setKeptGoals(null);
      setCurrentSkeleton(null);
      setSession(null);
      setOriginalSession(null);
      setSlidesCache({});
      setCompletedTasks([]);

      setSessionIdSynced(detail.id);
      setEntityType(resolvedEntityType);
      setCurrentCourseId(resolvedCourseId);

      lastSavedStepRef.current = detail.currentStep ?? null;
      const sessionIsFinished = detail.status === "complete" && detail.currentStep === "finished";
      setIsFinished(sessionIsFinished);
      setTemplateInfo(templateInfoFromDetail(detail));
      setKeptGoals(null);

      // R-2: use locally computed step order, not the stale component-state STEP_ORDER
      const localStepOrder = computeStepOrder(resolvedEntityType, resolvedCourseId);

      if (sessionIsFinished && detail.session) {
        if (detail.title) {
          detail.session.title = detail.title;
        }
        // Fully generated — parse draft to restore goals/input too if available
        if (detail.draftStateJson) {
          try {
            const draft: DraftState = JSON.parse(detail.draftStateJson);
            if (draft.workshopInput) {
              if (detail.title) draft.workshopInput.title = detail.title;
              setWorkshopInput(draft.workshopInput);
              savedInputRef.current = draft.workshopInput;
            }
            if (draft.refinedGoals) setRefinedGoals(draft.refinedGoals);
            setKeptGoals(draft.keptGoals ?? null);
            if (draft.skeleton) setCurrentSkeleton(draft.skeleton);
            if (draft.completedTasks) setCompletedTasks(draft.completedTasks);
          } catch (_) { /* best effort */ }
        }
        setSession(detail.session);
        setSlidesCache(detail.session.slides || {});
        setStep("final-review");
        const frIdx = localStepOrder.indexOf("final-review");
        setHighestStepIdx(frIdx >= 0 ? frIdx : localStepOrder.length - 1);
        setView("wizard");
        return;
      }

      let hasSession = !!detail.session;

      // Draft — restore all saved state
      if (detail.draftStateJson) {
        try {
          const draft: DraftState = JSON.parse(detail.draftStateJson);
          if (draft.workshopInput) {
            if (detail.title) draft.workshopInput.title = detail.title;
            setWorkshopInput(draft.workshopInput);
            // What was loaded is what is saved; only later edits count as unsaved settings
            savedInputRef.current = draft.workshopInput;
          }
          if (draft.refinedGoals)  setRefinedGoals(draft.refinedGoals);
          setKeptGoals(draft.keptGoals ?? null);
          if (draft.skeleton) setCurrentSkeleton(draft.skeleton);
          if (draft.session) {
            hasSession = true;
            if (detail.title) draft.session.title = detail.title;
            // Ensure cached slides from the server are merged into the draft session
            if (detail.session?.slides) {
              draft.session.slides = detail.session.slides;
            }
            setSession(draft.session);
          }
          if (draft.completedTasks) setCompletedTasks(draft.completedTasks);
        } catch (_) { /* best effort */ }
      }
      
      // Also restore slidesCache for the draft
      if (detail.session?.slides) {
        setSlidesCache(detail.session.slides);
      }

      // Navigate to where the user left off
      let targetStepStr = (detail.currentStep as string) ?? "input-1";
      // Opening a course always starts at its first step; every course step stays reachable
      if (resolvedEntityType === "COURSE") {
        targetStepStr = "input-1";
      }
      if (targetStepStr === "result" || targetStepStr === "skeleton") {
        if (hasSession) {
          targetStepStr = "timeline";
        } else {
          targetStepStr = "goals";
        }
      }
      if (targetStepStr === "timeline" && !hasSession) {
        targetStepStr = "goals";
      }
      if (targetStepStr === "finished") {
        targetStepStr = "final-review";
      }

      const targetIdx = localStepOrder.indexOf(targetStepStr as Step);
      // R-2: use localStepOrder — guaranteed to reflect resolved entity/course state.
      // Steps unlock by what the session contains, not by the step it was last saved at: once a
      // timetable exists, Timetable, Preparation and Final Review all work on it, so every step opens.
      // Courses likewise open with all of their steps reachable.
      const highestIdx = resolvedEntityType === "COURSE" || hasSession
        ? localStepOrder.length - 1
        : targetIdx >= 0 ? targetIdx : 0;

      // After a reload, return to the step the user was on, as long as it can be opened
      const requestedIdx = opts?.step ? localStepOrder.indexOf(opts.step as Step) : -1;
      const targetStep = (requestedIdx >= 0 && requestedIdx <= highestIdx ? opts!.step : targetStepStr) as Step;

      setStep(targetStep);
      setHighestStepIdx(highestIdx);
      setView("wizard");
    } catch (e) {
      if (handleAuthError(e)) return;
      if (opts?.silent) {
        // The page stored for a reload no longer opens (e.g. deleted): start on the dashboard
        try { sessionStorage.removeItem(LOCATION_KEY); } catch { /* ignore */ }
      } else {
        toast({
          title: "Could not load session",
          description: e instanceof Error ? (e.stack || e.message) : "Unknown error",
          variant: "destructive",
        });
      }
    } finally {
      setIsLoading(false);
    }
  };

  // ── Step handlers ────────────────────────────────────────────────────

  const handleStep1 = async (input: Partial<WorkshopInput>) => {
    // B-1: guard against rapid clicks creating duplicate sessions
    if (isLoading) return;
    setIsLoading(true);
    setIsFinished(false);
    setWorkshopInput(input);
    try {
      const draft = buildDraft(input);
      const id = await persistDraft(draft, "input-2", sessionIdRef.current, currentCourseId);
      if (!id) return; // not saved: stay on this step
      if (!sessionId) setSessionIdSynced(id);
      setStep("input-2");
    } finally {
      setIsLoading(false);
    }
  };

  // Called when user confirms activity selections on step 2 and clicks Next
  const handleStep2Activities = (activities: string[]) => {
    setWorkshopInput(prev => ({ ...prev, selectedActivities: activities }));
    setStep("input-2b");
  };

  const handleStep2 = async (input: WorkshopInput) => {
    // B-1: guard against rapid clicks creating duplicate sessions
    if (isLoading) return;
    setIsLoading(true);
    setIsFinished(false);
    setWorkshopInput(input);
    try {
      const draft = buildDraft(input);
      const id = await persistDraft(draft, entityType === "COURSE" ? "course-template" : "goals", sessionIdRef.current, currentCourseId);
      if (!id) return; // not saved: stay on this step
      if (!sessionId) setSessionIdSynced(id);
      setStep(entityType === "COURSE" ? "course-template" : "goals");
    } finally {
      setIsLoading(false);
    }
  };

  /** Last step of a course: save it and go back to the dashboard. */
  const handleCourseFinish = async () => {
    if (isLoading) return;
    setIsLoading(true);
    try {
      const id = await persistDraft(buildDraft(workshopInput), "result", sessionIdRef.current, currentCourseId);
      if (!id) { setIsLoading(false); return; } // not saved: stay on this step
      toast({ title: "Course saved", description: "Your course has been saved to the dashboard." });
      // C-3: short delay so the toast is visible before navigating away; keep the spinner until then
      setTimeout(() => handleReset(), 1500);
    } catch {
      setIsLoading(false);
    }
  };

  const handleGoalsEntered = async (goalsWithPriority: LearningGoalPlan[]) => {
    if (isGeneratingRef.current) return;
    isGeneratingRef.current = true;
    setIsFinished(false);
    setRefinedGoals(goalsWithPriority);
    
    const updatedInput = { ...workshopInput };
    if (!updatedInput.evaluateMappings || updatedInput.evaluateMappings.length === 0) {
      if (goalsWithPriority.length > 0 && goalsWithPriority.length <= 2) {
        // Best-fit activity per goal from its Bloom's/SOLO level, limited to the user's activities
        updatedInput.evaluateMappings = defaultEvaluateMappings(goalsWithPriority, updatedInput.selectedActivities || []);
      }
    }
    setWorkshopInput(updatedInput);

    setIsLoading(true);
    try {
      const skeleton = generateDefaultSkeleton(goalsWithPriority, updatedInput.duration || 90);
      setCurrentSkeleton(skeleton);
      
      // We must save the draft first. If the LLM generation times out, we don't want to create an orphaned session.
      setKeptGoals(null);
      const initialDraft = { ...buildDraft(updatedInput, goalsWithPriority, skeleton), keptGoals: undefined };
      const currentId = await persistDraft(initialDraft, "timeline", sessionIdRef.current, currentCourseId);
      if (!currentId) throw new Error("The session could not be saved, so no timetable was generated.");
      if (!sessionId) setSessionIdSynced(currentId);
      
      const skeletonWithId: SessionSkeleton = {
        ...skeleton,
        sessionId: currentId,
      };
      const result = await generateSession(
        goalsWithPriority,
        updatedInput as WorkshopInput,
        skeletonWithId
      );
      if (updatedInput.title?.trim()) {
        result.title = updatedInput.title.trim();
      }
      setSession(result);
      setOriginalSession(JSON.parse(JSON.stringify(result)));
      
      const draft = { ...buildDraft(updatedInput, goalsWithPriority, skeleton, result), keptGoals: undefined };
      await persistDraft(draft, "timeline", currentId, currentCourseId);
      setStep("timeline");
    } catch (err) {
      toast({
        title: "Session generation failed",
        description: err instanceof Error ? err.message : "Unknown error",
        variant: "destructive",
      });
    } finally {
      setIsLoading(false);
      isGeneratingRef.current = false;
    }
  };

  const handleReset = () => {
    flushDraft();
    setView("dashboard");
    setSessionIdSynced(null);
    setTemplateInfo(null);
    setKeptGoals(null);
    setCurrentCourseId(null);
    setEntityType("SESSION");
    setWorkshopInput({});
    savedInputRef.current = {};
    setRefinedGoals([]);
    setSession(null);
    setCurrentSkeleton(null);
    setCompletedTasks([]);
    setIsFinished(false);
    setIsLoading(false);
    setStep("input-1");
  };

  const toggleDark = () => {
    setDarkMode((d) => {
      const next = !d;
      document.documentElement.classList.toggle("dark", next);
      return next;
    });
  };

  const currentIdx = STEP_ORDER.indexOf(step);

  useEffect(() => {
    setHighestStepIdx(prev => Math.max(prev, currentIdx));
  }, [currentIdx]);

  // A session with a timetable can open every step (Timetable, Preparation and Final Review work on it)
  useEffect(() => {
    if (entityType === "SESSION" && session) setHighestStepIdx(STEP_ORDER.length - 1);
  }, [entityType, session, STEP_ORDER.length]);

  // Read before the effect below overwrites it with the dashboard the app starts on
  const restoreLocationRef = useRef(readLocation());

  // The same session open in two tabs: each tab saves its whole state, so the last save wins.
  // Tabs announce the session they have open; if another tab has the same one, both warn.
  const tabIdRef = useRef(Math.random().toString(36).slice(2));
  const warnedTwinRef = useRef<string | null>(null);
  useEffect(() => {
    if (typeof BroadcastChannel === "undefined" || view !== "wizard" || !sessionId) return;
    const channel = new BroadcastChannel("workshopper-open-sessions");
    const me = { id: sessionId, tab: tabIdRef.current };
    const warn = () => {
      if (warnedTwinRef.current === sessionId) return;
      warnedTwinRef.current = sessionId;
      toast({
        title: "Open in another tab",
        description: "This session is also open in another tab. Changes in one tab can overwrite the other's, so please keep working in one of them.",
        variant: "destructive",
      });
    };
    channel.onmessage = (e: MessageEvent<{ type: "open" | "here"; id: string; tab: string }>) => {
      if (e.data.tab === me.tab || e.data.id !== me.id) return;
      if (e.data.type === "open") channel.postMessage({ type: "here", ...me });
      warn();
    };
    channel.postMessage({ type: "open", ...me });
    return () => channel.close();
  }, [view, sessionId]);

  // Remember the current page; the dashboard (or an entity that is not saved yet) is stored as nothing
  useEffect(() => {
    try {
      if (view === "wizard" && sessionId) {
        const location: SavedLocation = { id: sessionId, type: entityType, step };
        sessionStorage.setItem(LOCATION_KEY, JSON.stringify(location));
      } else if (hasRestored && !isLoading) {
        sessionStorage.removeItem(LOCATION_KEY);
      }
    } catch { /* storage unavailable: a reload then starts on the dashboard */ }
  }, [view, sessionId, entityType, step, isLoading]);

  // After a reload, reopen the page the user was on (nothing to do if it was the dashboard)
  useEffect(() => {
    if (hasRestored) return;
    hasRestored = true;
    const location = restoreLocationRef.current;
    if (location) {
      resumeSession(location.id, { silent: true, type: location.type, step: location.step });
    }
  }, []);

  // Handle browser back button
  useEffect(() => {
    const handlePopState = () => {
      if (view === "wizard") {
        const guard = leaveGuardRef.current;
        if (guard && guard.isDirty()) {
          // Stay on the page until the user decides about the unsaved changes
          window.history.pushState({ page: "wizard" }, "");
          guard.confirmLeave(() => { flushDraft(); setView("dashboard"); });
        } else {
          flushDraft();
          setView("dashboard");
        }
      }
    };
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, [view, setView]);

  useEffect(() => {
    if (view === "wizard") {
      window.history.pushState({ page: "wizard" }, "");
    }
  }, [view]);

  // Reloading or closing the tab: send any waiting save, and let the browser ask first if input could be lost
  useEffect(() => {
    const handleBeforeUnload = (e: BeforeUnloadEvent) => {
      const unsaved = leaveGuardRef.current?.isDirty() ?? false;
      const waiting = pendingSave.current !== null;
      if (waiting) flushDraft();
      if (unsaved || waiting) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [flushDraft]);


  return {
    view,
    setView,
    step,
    setStep,
    highestStepIdx,
    setHighestStepIdx,
    darkMode,
    isLoading,
    setIsLoading,
    isFinished,
    setIsFinished,
    showTimelineBackConfirm,
    setShowTimelineBackConfirm,
    sessionId,
    currentCourseId,
    entityType,
    STEPS,
    STEP_ORDER,
    workshopInput,
    setWorkshopInput,
    refinedGoals,
    setRefinedGoals,
    templateInfo,
    setTemplateInfo,
    session,
    setSession,
    originalSession,
    currentSkeleton,
    setCurrentSkeleton,
    completedTasks,
    setCompletedTasks,
    slidesCache,
    setSlidesCache,
    persistDraft,
    buildDraft,
    startNewSession,
    startNewCourse,
    startSessionFromCourse,
    resumeSession,
    handleStep1,
    handleStep2Activities,
    handleStep2,
    handleCourseFinish,
    flushDraft,
    hasUnsavedSettings,
    saveSettings,
    discardSettings,
    keptGoals,
    keepGoalDraft,
    leaveGuardRef,
    confirmLeave,
    handleGoalsEntered,
    handleReset,
    toggleDark,
  };
}
