import { useState, useCallback, useRef, useEffect } from "react";
import { generateSession, getSessionDetail, saveDraft, finishSession, handleAuthError } from "@/lib/api";
import { toast } from "@/hooks/use-toast";
import type {
  WorkshopInput,
  LearningGoalPlan,
  WorkshopSession,
  SessionSkeleton,
  DraftState
} from "@/lib/workshop-generator";
import { generateDefaultSkeleton, SlideData } from "@/lib/workshop-generator";

type Step = "input-1" | "input-2" | "input-2b" | "lecture-summary" | "goals" | "timeline" | "prepare" | "final-review";

const ALL_STEPS: { id: Step; label: string }[] = [
  { id: "input-1",  label: "Setup" },
  { id: "input-2",  label: "Activities" },
  { id: "input-2b", label: "Materials" },
  { id: "lecture-summary", label: "Review" },
  { id: "goals",    label: "Goals" },
  { id: "timeline", label: "Timetable" },
  { id: "prepare",  label: "Preparation" },
  { id: "final-review", label: "Final Review" },
];

let hasRestored = false;

function computeStepOrder(entityType: "SESSION" | "LECTURE", lectureId: string | null): Step[] {
  return ALL_STEPS
    .filter(s => {
      if (entityType === "LECTURE") return s.id === "input-1" || s.id === "input-2" || s.id === "input-2b";
      if (!lectureId && s.id === "lecture-summary") return false;
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
  const [currentLectureId, setCurrentLectureId] = useState<string | null>(null);
  const [entityType, setEntityType] = useState<"SESSION" | "LECTURE">("SESSION");

  const STEPS = ALL_STEPS.filter(s => {
    if (entityType === "LECTURE") return s.id === "input-1" || s.id === "input-2" || s.id === "input-2b";
    if (!currentLectureId && s.id === "lecture-summary") return false;
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

  // Debounce ref for draft saves
  const draftSaveTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);



  /** Persist current draft state to the backend, debounced. Returns the session id. */
  const pendingResolvers = useRef<Array<(id: string) => void>>([]);

  const persistDraft = useCallback(
    async (
      draft: DraftState,
      currentStep: string,
      currentSessionId: string | null,
      type: "SESSION" | "LECTURE",
      lectureId: string | null
    ): Promise<string> => {
      if (draftSaveTimeout.current) clearTimeout(draftSaveTimeout.current);
      return new Promise((resolve) => {
        pendingResolvers.current.push(resolve);
        draftSaveTimeout.current = setTimeout(async () => {
          try {
            const id = await saveDraft(draft, currentSessionId, currentStep, type, lectureId ?? undefined);
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
        }, 300);
      });
    },
    []
  );

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
    }),
    [workshopInput, refinedGoals, currentSkeleton, session, completedTasks]
  );

  // ── Navigation helpers ──────────────────────────────────────────────

  const startNewSession = () => {
    setSessionIdSynced(null);
    setCurrentLectureId(null);
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

  const startNewLecture = () => {
    setSessionIdSynced(null);
    setCurrentLectureId(null);
    setEntityType("LECTURE");
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

  const startSessionFromLecture = async (lectureId: string) => {
    setIsLoading(true);
    try {
      const detail = await getSessionDetail(lectureId);
      if (detail.draftStateJson) {
        const draft: DraftState = JSON.parse(detail.draftStateJson);
        if (draft.workshopInput) {
          const inheritedInput = { ...draft.workshopInput };
          delete inheritedInput.title;
          setWorkshopInput(inheritedInput);
        }
      }
      setSessionIdSynced(null);
      setCurrentLectureId(lectureId);
      setEntityType("SESSION");
      setRefinedGoals([]);
      setSession(null);
      setCurrentSkeleton(null);
      setCompletedTasks([]);
      setSlidesCache({});
      setIsFinished(false);
      
      const idx = ALL_STEPS.filter(s => s.id !== "lecture-summary" || true).map(s => s.id).indexOf("lecture-summary");
      setHighestStepIdx(idx !== -1 ? idx : 2);
      setStep("lecture-summary");
      setView("wizard");
    } catch (e) {
      toast({ title: "Error", description: "Failed to load lecture settings", variant: "destructive" });
    } finally {
      setIsLoading(false);
    }
  };

  const resumeSession = async (id: string, opts?: { silent?: boolean }) => {
    setIsLoading(true);
    try {
      const detail = await getSessionDetail(id);
      // R-2: resolve entity type and lectureId locally so we can compute the correct
      // step order immediately, without waiting for React state to flush.
      const resolvedEntityType = detail.type ?? "SESSION";
      const resolvedLectureId = detail.lectureId ?? null;

      setSessionIdSynced(detail.id);
      setEntityType(resolvedEntityType);
      setCurrentLectureId(resolvedLectureId);

      const sessionIsFinished = detail.status === "complete" && detail.currentStep === "finished";
      setIsFinished(sessionIsFinished);

      // R-2: use locally computed step order, not the stale component-state STEP_ORDER
      const localStepOrder = computeStepOrder(resolvedEntityType, resolvedLectureId);

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
          if (draft.skeleton) setCurrentSkeleton(draft.skeleton);
          if (draft.session) {
            hasSession = true;
            if (detail.title) draft.session.title = detail.title;
            // Ensure cached slides from the backend are merged into the draft session
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
      if (resolvedEntityType === "LECTURE" && targetStepStr === "result") {
        targetStepStr = "input-2";
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
      // R-2: use localStepOrder — guaranteed to reflect resolved entity/lecture state
      setHighestStepIdx(targetIdx >= 0 ? targetIdx : 0);
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
      const id = await persistDraft(draft, "input-2", sessionIdRef.current, entityType, currentLectureId);
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
      const id = await persistDraft(draft, entityType === "LECTURE" ? "result" : "goals", sessionIdRef.current, entityType, currentLectureId);
      if (!sessionId) setSessionIdSynced(id);
      if (entityType === "LECTURE") {
        toast({ title: "Lecture successfully created", description: "Your lecture has been saved to the dashboard." });
        // C-3: short delay so the toast is visible before navigating away
        setTimeout(() => handleReset(), 1500);
      } else {
        setStep("goals");
      }
    } finally {
      if (entityType !== "LECTURE") setIsLoading(false);
      // For LECTURE, handleReset() will run after 1500ms — keep spinner until then
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
        const fallbacks = ["Quiz", "Think-Pair-Share"];
        const avail = (updatedInput.selectedActivities && updatedInput.selectedActivities.length > 0)
          ? updatedInput.selectedActivities
          : fallbacks;
        
        updatedInput.evaluateMappings = goalsWithPriority.map((g, idx) => ({
          method: avail[idx % avail.length],
          lgIds: [g.id]
        }));
      }
    }
    setWorkshopInput(updatedInput);

    setIsLoading(true);
    try {
      const skeleton = generateDefaultSkeleton(goalsWithPriority, updatedInput.duration || 90);
      setCurrentSkeleton(skeleton);
      
      // We must save the draft first. If the LLM generation times out, we don't want to create an orphaned session.
      const initialDraft = buildDraft(updatedInput, goalsWithPriority, skeleton);
      const currentId = await persistDraft(initialDraft, "timeline", sessionIdRef.current, entityType, currentLectureId);
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
      
      const draft = buildDraft(updatedInput, goalsWithPriority, skeleton, result);
      await persistDraft(draft, "timeline", currentId, entityType, currentLectureId);
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
    if (draftSaveTimeout.current) clearTimeout(draftSaveTimeout.current);
    setView("dashboard");
    setSessionIdSynced(null);
    setCurrentLectureId(null);
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
    "lecture-summary": { title: "Loading…",             sub: "" },
    "goals":    { title: "Loading…",                    sub: "" },
    "timeline": { title: "Loading…", sub: "" },
    "prepare":  { title: "Loading…",                    sub: "" },
    "final-review": { title: "Loading…",                sub: "" },
  };


  // Handle browser back button
  useEffect(() => {
    const handlePopState = (e: PopStateEvent) => {
      if (view === "wizard") {
        setView("dashboard");
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
    currentLectureId,
    setCurrentLectureId,
    entityType,
    setEntityType,
    STEPS,
    STEP_ORDER,
    workshopInput,
    setWorkshopInput,
    refinedGoals,
    setRefinedGoals,
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
    startNewLecture,
    startSessionFromLecture,
    resumeSession,
    handleStep1,
    handleStep2Activities,
    handleStep2,
    handleGoalsEntered,
    handleReset,
    toggleDark,
    currentIdx,
    loadingMessages
  };
}
