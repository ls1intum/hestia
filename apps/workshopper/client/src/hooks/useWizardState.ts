import { useState, useCallback, useRef, useEffect } from "react";
import { generateSession, getSessionDetail, getCourseDetail, saveDraft, saveCourseDraft, finishSession, handleAuthError } from "@/lib/api";
import { toast } from "@/hooks/use-toast";
import type {
  WorkshopInput,
  LearningGoalPlan,
  WorkshopSession,
  SessionSkeleton,
  DraftState,
  TemplateInfo,
  KeptGoal
} from "@/lib/workshop-generator";

/** Lets a step with unsaved input intercept navigation away from it. */
export interface LeaveGuard {
  /** True while there is input that has not been saved yet. */
  isDirty(): boolean;
  /** Shows the step's save/discard/stay dialog and calls `proceed` once the user chose to leave. */
  confirmLeave(proceed: () => void): void;
}
import { defaultEvaluateMappings } from "@/lib/activity-taxonomy";
import { generateDefaultSkeleton, SlideData, templateInfoFromDetail } from "@/lib/workshop-generator";

type Step = "input-1" | "input-2" | "input-2b" | "course-template" | "course-summary" | "goals" | "timeline" | "prepare" | "final-review";

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
    if (id) {
      sessionStorage.setItem("workshopper_session_id", id);
    } else {
      sessionStorage.removeItem("workshopper_session_id");
    }
  };
  const [currentCourseId, setCurrentCourseId] = useState<string | null>(null);
  const [courseTitle, setCourseTitle] = useState<string | null>(null);
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



  /** Persist current draft state to the server, debounced. Returns the session id. */
  const pendingResolvers = useRef<Array<(id: string) => void>>([]);
  /** The save waiting for its debounce, so navigation can send it right away instead of dropping it. */
  const pendingSave = useRef<(() => Promise<void>) | null>(null);
  /** The step the draft was last saved with, so saves that aren't a step change can keep it. */
  const lastSavedStepRef = useRef<string | null>(null);

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

  const persistDraft = useCallback(
    async (
      draft: DraftState,
      currentStep: string,
      currentSessionId: string | null,
      courseId: string | null
    ): Promise<string> => {
      if (draftSaveTimeout.current) clearTimeout(draftSaveTimeout.current);
      lastSavedStepRef.current = currentStep;
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
            handleAuthError(e);
            console.warn("Draft save failed", e);
            const resolvers = pendingResolvers.current;
            pendingResolvers.current = [];
            resolvers.forEach(r => r(currentSessionId ?? ""));
          }
        };
        pendingSave.current = run;
        draftSaveTimeout.current = setTimeout(run, 300);
      });
    },
    [entityType]
  );

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
        }
      }
      setSessionIdSynced(null);
      setCurrentCourseId(courseId);
      setCourseTitle(detail.title || "Untitled Course");
      setTemplateInfo(templateInfoFromDetail(detail));
      setKeptGoals(null);
      setEntityType("SESSION");
      setRefinedGoals([]);
      setSession(null);
      setCurrentSkeleton(null);
      setCompletedTasks([]);
      setSlidesCache({});
      setIsFinished(false);
      
      const idx = ALL_STEPS.filter(s => s.id !== "course-summary" || true).map(s => s.id).indexOf("course-summary");
      setHighestStepIdx(idx !== -1 ? idx : 2);
      setStep("course-summary");
      setView("wizard");
    } catch (e) {
      toast({ title: "Error", description: "Failed to load course settings", variant: "destructive" });
    } finally {
      setIsLoading(false);
    }
  };

  const resumeSession = async (id: string, opts?: { silent?: boolean; type?: "SESSION" | "COURSE" }) => {
    setIsLoading(true);
    try {
      
      const resolvedEntityType = opts?.type ?? "SESSION";
      const detail = resolvedEntityType === "COURSE" ? await getCourseDetail(id) : await getSessionDetail(id);
      // R-2: resolve courseId locally so we can compute the correct step order immediately
      const resolvedCourseId = detail.courseId ?? null;
      // Determine entity type from presence of courseId: sessions with a courseId are SESSION type
      

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

      const targetStep = targetStepStr as Step;
      const targetIdx = localStepOrder.indexOf(targetStep);

      setStep(targetStep);
      // R-2: use localStepOrder — guaranteed to reflect resolved entity/course state
      setHighestStepIdx(resolvedEntityType === "COURSE"
        ? localStepOrder.length - 1
        : targetIdx >= 0 ? targetIdx : 0);
      setView("wizard");
    } catch (e) {
      if (handleAuthError(e)) return;
      if (opts?.silent) {
        sessionStorage.removeItem("workshopper_session_id");
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
      await persistDraft(buildDraft(workshopInput), "result", sessionIdRef.current, currentCourseId);
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

  useEffect(() => {
    if (hasRestored) return;
    hasRestored = true;
    const savedId = sessionStorage.getItem("workshopper_session_id");
    if (savedId) {
      resumeSession(savedId, { silent: true });
    }
  }, []);

  const loadingMessages: Record<Step, { title: string; sub: string }> = {
    "input-1":  { title: "Loading…",                    sub: "" },
    "input-2":  { title: "Loading…",                    sub: "" },
    "input-2b": { title: "Loading…",                    sub: "" },
    "course-template": { title: "Loading…",            sub: "" },
    "course-summary": { title: "Loading…",             sub: "" },
    "goals":    { title: "Loading…",                    sub: "" },
    "timeline": { title: "Loading…", sub: "" },
    "prepare":  { title: "Loading…",                    sub: "" },
    "final-review": { title: "Loading…",                sub: "" },
  };


  // Handle browser back button
  useEffect(() => {
    const handlePopState = (e: PopStateEvent) => {
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

  // ── Dashboard view ────────────────────────────────────────────────────

  return {
    view,
    setView,
    step,
    setStep,
    highestStepIdx,
    setHighestStepIdx,
    darkMode,
    setDarkMode,
    isLoading,
    setIsLoading,
    isFinished,
    setIsFinished,
    showTimelineBackConfirm,
    setShowTimelineBackConfirm,
    isGeneratingRef,
    sessionId,
    setSessionId,
    sessionIdRef,
    setSessionIdSynced,
    currentCourseId,
    setCurrentCourseId,
    entityType,
    setEntityType,
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
    setOriginalSession,
    currentSkeleton,
    setCurrentSkeleton,
    completedTasks,
    setCompletedTasks,
    slidesCache,
    setSlidesCache,
    draftSaveTimeout,
    pendingResolvers,
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
    keptGoals,
    keepGoalDraft,
    leaveGuardRef,
    confirmLeave,
    handleGoalsEntered,
    handleReset,
    toggleDark,
    currentIdx,
    loadingMessages,
    courseTitle
  };
}
