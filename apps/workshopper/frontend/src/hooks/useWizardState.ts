import { useState, useCallback, useRef, useEffect } from "react";
import { toast } from "@/hooks/use-toast";
import { generateSession, getSessionDetail, saveDraft, handleAuthError } from "@/lib/api";
import { generateDefaultSkeleton } from "@/lib/workshop-generator";
import type {
  WorkshopInput,
  LearningGoalPlan,
  WorkshopSession,
  SessionSkeleton,
  DraftState,
  SlideData
} from "@/lib/workshop-generator";

export type Step = "input-1" | "input-2" | "input-2b" | "lecture-summary" | "goals" | "timeline" | "prepare" | "final-review";

export const ALL_STEPS: { id: Step; label: string }[] = [
  { id: "input-1",  label: "Setup" },
  { id: "input-2",  label: "Activities" },
  { id: "input-2b", label: "Materials" },
  { id: "lecture-summary", label: "Review" },
  { id: "goals",    label: "Goals" },
  { id: "timeline", label: "Timetable" },
  { id: "prepare",  label: "Preparation" },
  { id: "final-review", label: "Final Review" },
];

export function computeStepOrder(entityType: "SESSION" | "LECTURE", lectureId: string | null): Step[] {
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
  const [step, setStep] = useState<Step>("input-1");
  const [highestStepIdx, setHighestStepIdx] = useState(0);
  const [darkMode, setDarkMode] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [isFinished, setIsFinished] = useState(false);
  const [showTimelineBackConfirm, setShowTimelineBackConfirm] = useState(false);
  const isGeneratingRef = useRef(false);


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

  const STEP_ORDER = computeStepOrder(entityType, currentLectureId);

  // Auto-update highestStepIdx when step changes
  useEffect(() => {
    const currentIdx = STEP_ORDER.indexOf(step);
    setHighestStepIdx(prev => Math.max(prev, currentIdx));
  }, [step, STEP_ORDER]);


  const [workshopInput, setWorkshopInput] = useState<Partial<WorkshopInput>>({});
  const [refinedGoals, setRefinedGoals] = useState<LearningGoalPlan[]>([]);
  const [session, setSession] = useState<WorkshopSession | null>(null);
  const [originalSession, setOriginalSession] = useState<WorkshopSession | null>(null);
  const [currentSkeleton, setCurrentSkeleton] = useState<SessionSkeleton | null>(null);
  const [completedTasks, setCompletedTasks] = useState<string[]>([]);
  const [slidesCache, setSlidesCache] = useState<Record<number, SlideData[]>>({});

  const draftSaveTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);
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
            console.warn("Draft save failed", e);
            handleAuthError(e);
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
      setHighestStepIdx(0);
      setStep("input-1");
      setView("wizard");
    } catch (e) {
      if (!handleAuthError(e)) {
        toast({
          title: "Failed to load lecture data",
          description: e instanceof Error ? e.message : "Unknown error",
          variant: "destructive",
        });
      }
    } finally {
      setIsLoading(false);
    }
  };

  const resumeSession = async (id: string, opts?: { silent?: boolean }) => {
    if (!opts?.silent) setIsLoading(true);
    try {
      const detail = await getSessionDetail(id);
      setSessionIdSynced(detail.id);
      setEntityType(detail.type || "SESSION");
      setCurrentLectureId(detail.lectureId || null);
      if (detail.draftStateJson) {
        const draft: DraftState = JSON.parse(detail.draftStateJson);
        setWorkshopInput(draft.workshopInput || {});
        setRefinedGoals(draft.refinedGoals || []);
        setCurrentSkeleton(draft.skeleton || null);
        setSession(draft.session || null);
        setOriginalSession(draft.session ? JSON.parse(JSON.stringify(draft.session)) : null);
        setCompletedTasks(draft.completedTasks || []);
      }
      const restoredStep = detail.currentStep as Step || "input-1";
      setStep(restoredStep);
      const computedOrder = computeStepOrder(detail.type || "SESSION", detail.lectureId || null);
      setHighestStepIdx(Math.max(computedOrder.indexOf(restoredStep), 0));
      setView("wizard");
    } catch (e) {
      if (!handleAuthError(e)) {
        toast({
          title: "Failed to load session",
          description: e instanceof Error ? e.message : "Unknown error",
          variant: "destructive",
        });
      }
    } finally {
      if (!opts?.silent) setIsLoading(false);
    }
  };

  const handleStep1 = async (input: Partial<WorkshopInput>) => {
    const isNew = Object.keys(workshopInput).length === 0;
    setWorkshopInput(input);
    setIsLoading(true);
    const draft = buildDraft(input);
    try {
      const id = await persistDraft(draft, "input-2", sessionIdRef.current, entityType, currentLectureId);
      if (isNew && !sessionIdRef.current) setSessionIdSynced(id);
      setStep("input-2");
    } catch (err) {
      if (!handleAuthError(err)) {
        toast({ title: "Failed to save draft", description: err instanceof Error ? err.message : "Unknown error", variant: "destructive" });
      }
    } finally {
      setIsLoading(false);
    }
  };

  const handleStep2 = async (input: WorkshopInput) => {
    setWorkshopInput(input);
    if (entityType !== "LECTURE") setIsLoading(true);
    const draft = buildDraft(input);
    try {
      const id = await persistDraft(draft, entityType === "LECTURE" ? "result" : "goals", sessionIdRef.current, entityType, currentLectureId);
      if (!sessionIdRef.current) setSessionIdSynced(id);
      if (entityType === "LECTURE") {
        setIsFinished(true);
        setStep("final-review");
      } else {
        setStep("goals");
      }
    } catch (err) {
      if (!handleAuthError(err)) {
        toast({ title: "Failed to save draft", description: err instanceof Error ? err.message : "Unknown error", variant: "destructive" });
      }
    } finally {
      if (entityType !== "LECTURE") setIsLoading(false);
    }
  };

  const handleGoalsEntered = async (goalsWithPriority: LearningGoalPlan[]) => {
    if (isGeneratingRef.current) return;
    isGeneratingRef.current = true;
    setRefinedGoals(goalsWithPriority);
    
    let updatedInput = { ...workshopInput };
    if (!updatedInput.evaluateMappings || updatedInput.evaluateMappings.length === 0) {
      const avail = updatedInput.availableMaterials || [];
      if (avail.length > 0) {
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
      if (!handleAuthError(err)) {
        toast({
          title: "Session generation failed",
          description: err instanceof Error ? err.message : "Unknown error",
          variant: "destructive",
        });
      }
    } finally {
      setIsLoading(false);
      isGeneratingRef.current = false;
    }
  };

  const handleReset = () => {
    if (draftSaveTimeout.current) clearTimeout(draftSaveTimeout.current);
    startNewSession();
  };

  const switchStep = (target: Step) => {
    const targetIdx = STEP_ORDER.indexOf(target);
    const currIdx = STEP_ORDER.indexOf(step);
    if (targetIdx > highestStepIdx) return;
    if (step === "timeline" && targetIdx < currIdx && !showTimelineBackConfirm) {
      setShowTimelineBackConfirm(true);
      return;
    }
    setStep(target);
    setShowTimelineBackConfirm(false);
    if (sessionId) {
      persistDraft(buildDraft(), target, sessionId, entityType, currentLectureId);
    }
  };

  return {
    view, setView,
    step, setStep,
    highestStepIdx, setHighestStepIdx,
    darkMode, setDarkMode,
    isLoading, setIsLoading,
    isFinished, setIsFinished,
    showTimelineBackConfirm, setShowTimelineBackConfirm,
    sessionId, setSessionIdSynced,
    currentLectureId, setCurrentLectureId,
    entityType, setEntityType,
    workshopInput, setWorkshopInput,
    refinedGoals, setRefinedGoals,
    session, setSession,
    originalSession, setOriginalSession,
    currentSkeleton, setCurrentSkeleton,
    completedTasks, setCompletedTasks,
    slidesCache, setSlidesCache,
    STEP_ORDER,
    buildDraft,
    persistDraft,
    startNewSession,
    startNewLecture,
    startSessionFromLecture,
    resumeSession,
    handleStep1,
    handleStep2,
    handleGoalsEntered,
    handleReset,
    switchStep,
    draftSaveTimeout
  };
}
