import { useState, useCallback, useRef, useEffect } from "react";
import { Loader2, Moon, Sun, GraduationCap, ArrowLeft } from "lucide-react";
import WorkshopFormStep1 from "@/components/WorkshopFormStep1";
import WorkshopFormStep2 from "@/components/WorkshopFormStep2";
import WorkshopGoalEntry from "@/components/WorkshopGoalEntry";
import WorkshopSkeletonBuilder from "@/components/WorkshopSkeletonBuilder";
import WorkshopResult from "@/components/WorkshopResult";
import WorkshopPreparation from "@/components/WorkshopPreparation";
import SessionsDashboard from "@/components/SessionsDashboard";
import { Toaster } from "@/components/ui/toaster";
import { Button } from "@/components/ui/button";
import { generateSession, getSessionDetail, saveDraft, finishSession } from "@/lib/api";
import { toast } from "@/hooks/use-toast";
import type {
  WorkshopInput,
  LearningGoalPlan,
  WorkshopSession,
  SessionSkeleton,
  DraftState,
} from "@/lib/workshop-generator";

import LectureSummary from "@/components/LectureSummary";

type Step = "input-1" | "input-2" | "lecture-summary" | "goals" | "skeleton" | "result" | "prepare";

const ALL_STEPS: { id: Step; label: string }[] = [
  { id: "input-1",  label: "Setup" },
  { id: "input-2",  label: "Activities" },
  { id: "lecture-summary", label: "Review" },
  { id: "goals",    label: "Goals" },
  { id: "skeleton", label: "Timetable" },
  { id: "result",   label: "Session" },
  { id: "prepare",  label: "Preparation" },
];

export default function App() {
  const [view, setView] = useState<"dashboard" | "wizard">("dashboard");
  const [step, setStep]         = useState<Step>("input-1");
  const [highestStepIdx, setHighestStepIdx] = useState(0);
  const [darkMode, setDarkMode] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [isFinished, setIsFinished] = useState(false);

  // Current session being created/edited
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [currentLectureId, setCurrentLectureId] = useState<string | null>(null);
  const [entityType, setEntityType] = useState<"SESSION" | "LECTURE">("SESSION");

  const STEPS = ALL_STEPS.filter(s => {
    if (entityType === "LECTURE") return s.id === "input-1" || s.id === "input-2";
    if (!currentLectureId && s.id === "lecture-summary") return false;
    return true;
  });
  const STEP_ORDER = STEPS.map((s) => s.id);

  // All form state
  const [workshopInput, setWorkshopInput] = useState<Partial<WorkshopInput>>({});
  const [refinedGoals,  setRefinedGoals]  = useState<LearningGoalPlan[]>([]);
  const [session,       setSession]       = useState<WorkshopSession | null>(null);
  const [currentSkeleton, setCurrentSkeleton] = useState<SessionSkeleton | null>(null);
  const [completedTasks, setCompletedTasks] = useState<string[]>([]);

  // Debounce ref for draft saves
  const draftSaveTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);



  /** Persist current draft state to the backend, debounced. Returns the session id. */
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
        draftSaveTimeout.current = setTimeout(async () => {
          try {
            const id = await saveDraft(draft, currentSessionId, currentStep, type, lectureId ?? undefined);
            resolve(id);
          } catch (e) {
            console.warn("Draft save failed", e);
            resolve(currentSessionId ?? "");
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
    setSessionId(null);
    setCurrentLectureId(null);
    setEntityType("SESSION");
    setWorkshopInput({});
    setRefinedGoals([]);
    setSession(null);
    setCurrentSkeleton(null);
    setCompletedTasks([]);
    setIsFinished(false);
    setHighestStepIdx(0);
    setStep("input-1");
    setView("wizard");
  };

  const startNewLecture = () => {
    setSessionId(null);
    setCurrentLectureId(null);
    setEntityType("LECTURE");
    setWorkshopInput({});
    setRefinedGoals([]);
    setSession(null);
    setCurrentSkeleton(null);
    setCompletedTasks([]);
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
        if (draft.workshopInput) setWorkshopInput(draft.workshopInput);
      }
      setSessionId(null);
      setCurrentLectureId(lectureId);
      setEntityType("SESSION");
      setRefinedGoals([]);
      setSession(null);
      setCurrentSkeleton(null);
      setCompletedTasks([]);
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

  const resumeSession = async (id: string) => {
    setIsLoading(true);
    try {
      const detail = await getSessionDetail(id);
      setSessionId(detail.id);
      setEntityType(detail.type ?? "SESSION");
      setCurrentLectureId(detail.lectureId ?? null);

      const sessionIsFinished = detail.status === "complete" && detail.currentStep === "finished";
      setIsFinished(sessionIsFinished);

      if (sessionIsFinished && detail.session) {
        if (detail.title) {
          detail.session.title = detail.title;
        }
        // Fully generated — parse draft to restore goals/input too if available
        if (detail.draftStateJson) {
          try {
            const draft: DraftState = JSON.parse(detail.draftStateJson);
            if (draft.workshopInput) setWorkshopInput(draft.workshopInput);
            if (draft.refinedGoals) setRefinedGoals(draft.refinedGoals);
            if (draft.skeleton) setCurrentSkeleton(draft.skeleton);
            if (draft.completedTasks) setCompletedTasks(draft.completedTasks);
          } catch (_) { /* best effort */ }
        }
        setSession(detail.session);
        setStep("result");
        setHighestStepIdx(STEP_ORDER.indexOf("result"));
        setView("wizard");
        return;
      }

      // Draft — restore all saved state
      if (detail.draftStateJson) {
        try {
          const draft: DraftState = JSON.parse(detail.draftStateJson);
          if (draft.workshopInput) setWorkshopInput(draft.workshopInput);
          if (draft.refinedGoals)  setRefinedGoals(draft.refinedGoals);
          if (draft.skeleton) setCurrentSkeleton(draft.skeleton);
          if (draft.session) {
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

      // Navigate to where the user left off
      let targetStep = (detail.currentStep as Step) ?? "input-1";
      if (detail.type === "LECTURE" && targetStep === "result") {
        targetStep = "input-2";
      }
      
      setStep(targetStep);
      setHighestStepIdx(STEP_ORDER.indexOf(targetStep));
      setView("wizard");
    } catch (e) {
      toast({
        title: "Could not load session",
        description: e instanceof Error ? e.message : "Unknown error",
        variant: "destructive",
      });
    } finally {
      setIsLoading(false);
    }
  };

  // ── Step handlers ────────────────────────────────────────────────────

  const handleStep1 = async (input: Partial<WorkshopInput>) => {
    setIsFinished(false);
    setWorkshopInput(input);
    const draft = buildDraft(input);
    const id = await persistDraft(draft, "input-2", sessionId, entityType, currentLectureId);
    if (!sessionId) setSessionId(id);
    setStep("input-2");
  };

  const handleStep2 = async (input: WorkshopInput) => {
    setIsFinished(false);
    setWorkshopInput(input);
    const draft = buildDraft(input);
    const id = await persistDraft(draft, entityType === "LECTURE" ? "result" : "goals", sessionId, entityType, currentLectureId);
    if (!sessionId) setSessionId(id);
    if (entityType === "LECTURE") {
      toast({ title: "Lecture successfully created" });
      handleReset();
    } else {
      setStep("goals");
    }
  };

  const handleGoalsEntered = async (goals: LearningGoalPlan[], uploadedMaterialsText?: string) => {
    setIsFinished(false);
    setRefinedGoals(goals);
    
    // Update workshopInput with the uploaded materials text if provided
    const updatedInput = { ...workshopInput };
    if (uploadedMaterialsText !== undefined) {
      updatedInput.uploadedMaterialsText = uploadedMaterialsText;
      setWorkshopInput(updatedInput);
    }
    
    const draft = buildDraft(updatedInput, goals);
    const id = await persistDraft(draft, "skeleton", sessionId, entityType, currentLectureId);
    if (!sessionId) setSessionId(id);
    setStep("skeleton");
  };

  const handleSkeletonConfirmed = async (
    skeleton: SessionSkeleton,
    goals: LearningGoalPlan[]
  ) => {
    if (!workshopInput) return;
    setIsFinished(false);
    setCurrentSkeleton(skeleton);
    setIsLoading(true);
    try {
      // Pass the current sessionId inside skeleton so backend can update it
      const skeletonWithId: SessionSkeleton = {
        ...skeleton,
        sessionId: sessionId ?? undefined,
      };
      const result = await generateSession(
        goals,
        workshopInput as WorkshopInput,
        skeletonWithId
      );
      setSession(result);
      // If the backend returned a new id (first-time create), sync it
      if (result.id && result.id !== sessionId) {
        setSessionId(result.id);
      }
      setStep("result");
    } catch (err) {
      toast({
        title: "Session generation failed",
        description: err instanceof Error ? err.message : "Unknown error",
        variant: "destructive",
      });
    } finally {
      setIsLoading(false);
    }
  };

  const handleReset = () => {
    if (draftSaveTimeout.current) clearTimeout(draftSaveTimeout.current);
    setView("dashboard");
    setSessionId(null);
    setCurrentLectureId(null);
    setEntityType("SESSION");
    setWorkshopInput({});
    setRefinedGoals([]);
    setSession(null);
    setCurrentSkeleton(null);
    setCompletedTasks([]);
    setIsFinished(false);
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

  const loadingMessages: Record<Step, { title: string; sub: string }> = {
    "input-1":  { title: "Loading…",                    sub: "" },
    "input-2":  { title: "Loading…",                    sub: "" },
    "lecture-summary": { title: "Loading…",             sub: "" },
    "goals":    { title: "Loading…",                    sub: "" },
    "skeleton": { title: "Generating your session plan…", sub: "This may take a moment." },
    "result":   { title: "Generating your session plan…", sub: "This may take a moment." },
    "prepare":  { title: "Loading…",                    sub: "" },
  };

  // ── Dashboard view ────────────────────────────────────────────────────
  if (view === "dashboard") {
    return (
      <>
        <div className="absolute top-4 right-4 z-10">
          <Button variant="ghost" size="icon" onClick={toggleDark} aria-label="Toggle dark mode">
            {darkMode ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          </Button>
        </div>
        <SessionsDashboard
          onNewSession={startNewSession}
          onNewLecture={startNewLecture}
          onNewSessionFromLecture={startSessionFromLecture}
          onResumeSession={resumeSession}
        />
        {isLoading && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-background/70 backdrop-blur-sm">
            <div className="flex flex-col items-center gap-3 text-center">
              <Loader2 className="h-10 w-10 animate-spin text-primary" />
              <p className="font-body font-medium text-foreground">Loading session…</p>
            </div>
          </div>
        )}
        <Toaster />
      </>
    );
  }

  // ── Wizard view ────────────────────────────────────────────────────────
  return (
    <div className="min-h-screen bg-background">
      {/* Header */}
      <header className="sticky top-0 z-30 border-b border-border/50 bg-background/80 backdrop-blur-md">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 h-20 flex items-center justify-between gap-4 overflow-x-auto no-scrollbar">
          <button onClick={handleReset} className="flex items-center gap-2 hover:opacity-80 transition-opacity shrink-0">
            <ArrowLeft className="h-5 w-5 text-muted-foreground" />
            <GraduationCap className="h-8 w-8 text-primary" />
            <span className="font-display font-bold text-2xl text-foreground">Workshopper</span>
          </button>

          <div className="hidden sm:flex items-center justify-center flex-1 gap-1.5 text-sm font-body text-muted-foreground whitespace-nowrap px-4">
            {STEPS.map((s, i) => {
              const isClickable = i <= highestStepIdx && !isLoading;
              return (
                <span key={s.id} className="flex items-center gap-1.5">
                  <button
                    onClick={() => { if (isClickable) setStep(s.id); }}
                    disabled={!isClickable && step !== s.id}
                    className={`flex items-center gap-1.5 transition-opacity ${
                      isClickable ? "cursor-pointer hover:opacity-70" 
                      : i > highestStepIdx ? "cursor-not-allowed opacity-50" 
                      : "cursor-default"
                    }`}
                  >
                    <span className={`w-6 h-6 rounded-full flex items-center justify-center font-semibold transition-colors ${
                      step === s.id ? "bg-primary text-primary-foreground"
                      : i <= highestStepIdx ? "bg-primary/30 text-primary"
                      : "bg-muted text-muted-foreground"
                    }`}>
                      {i + 1}
                    </span>
                    <span className={step === s.id ? "text-foreground font-medium" : ""}>{s.label}</span>
                  </button>
                  {i < STEPS.length - 1 && <span className="mx-1 opacity-30">›</span>}
                </span>
              );
            })}
          </div>

          <Button variant="ghost" size="icon" onClick={toggleDark} aria-label="Toggle dark mode" className="shrink-0">
            {darkMode ? <Sun className="h-5 w-5" /> : <Moon className="h-5 w-5" />}
          </Button>
        </div>
      </header>

      {/* Main */}
      <main className="max-w-5xl mx-auto px-4 sm:px-6 py-8">
        {step === "input-1" && (
          <WorkshopFormStep1 onNext={handleStep1} isLoading={isLoading} initialInput={workshopInput} entityType={entityType} />
        )}

        {step === "input-2" && (
          <WorkshopFormStep2
            initialInput={workshopInput}
            onGenerate={handleStep2}
            isLoading={isLoading}
            onBack={() => setStep("input-1")}
          />
        )}

        {step === "lecture-summary" && currentLectureId && (
          <LectureSummary
            settings={workshopInput}
            onEdit={() => setStep("input-1")}
            onContinue={() => {
              // we don't save draft here yet, since user didn't enter anything new, just goes to goals
              setStep("goals");
            }}
            isLoading={isLoading}
          />
        )}

        {step === "goals" && (
          <WorkshopGoalEntry
            initialInput={workshopInput}
            onBack={() => currentLectureId ? setStep("lecture-summary") : setStep("input-2")}
            onContinue={handleGoalsEntered}
            isLoading={isLoading}
            initialGoals={refinedGoals}
          />
        )}

        {step === "skeleton" && (
          <WorkshopSkeletonBuilder
            goals={refinedGoals}
            totalDuration={workshopInput.duration || 90}
            initialSkeleton={currentSkeleton ?? undefined}
            onBack={() => setStep("goals")}
            onContinue={handleSkeletonConfirmed}
            isLoading={isLoading}
          />
        )}

        {step === "result" && session && (
          <WorkshopResult
            session={session}
            goals={refinedGoals}
            meta={workshopInput as WorkshopInput}
            onBack={() => setStep("skeleton")}
            onNext={() => {
              setStep("prepare");
              persistDraft(buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, session), isFinished ? "finished" : "prepare", sessionId, entityType, currentLectureId);
            }}
            onSaveSession={(updatedSession) => {
              setSession(updatedSession);
              persistDraft(buildDraft(workshopInput, refinedGoals, undefined, updatedSession), isFinished ? "finished" : "result", sessionId, entityType, currentLectureId);
            }}
          />
        )}

        {step === "prepare" && session && (
          <WorkshopPreparation
            session={session}
            goals={refinedGoals}
            meta={workshopInput as WorkshopInput}
            completedTasks={completedTasks}
            onUpdateTasks={(tasks, isAllDone) => {
              setCompletedTasks(tasks);
              let nextStep = isFinished ? "finished" : "prepare";
              if (isAllDone && !isFinished) {
                setIsFinished(true);
                nextStep = "finished";
              }
              persistDraft(buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, session, tasks), nextStep, sessionId, entityType, currentLectureId);
            }}
            onBack={() => setStep("result")}
            onDone={async (latestTasks) => {
              const safeTasks = Array.isArray(latestTasks) ? latestTasks : completedTasks;
              if (sessionId) {
                try {
                  setIsFinished(true);
                  await saveDraft(
                    buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, session, safeTasks),
                    sessionId,
                    "finished",
                    entityType,
                    currentLectureId ?? undefined
                  );
                  await finishSession(sessionId);
                } catch (e) {
                  toast({ title: "Failed to mark as finished", description: String(e), variant: "destructive" });
                }
              }
              handleReset();
            }}
          />
        )}
      </main>

      {isLoading && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-background/70 backdrop-blur-sm">
          <div className="flex flex-col items-center gap-3 text-center">
            <Loader2 className="h-10 w-10 animate-spin text-primary" />
            <p className="font-body font-medium text-foreground">{loadingMessages[step].title}</p>
            <p className="text-sm text-muted-foreground font-body">{loadingMessages[step].sub}</p>
          </div>
        </div>
      )}

      <Toaster />
    </div>
  );
}
