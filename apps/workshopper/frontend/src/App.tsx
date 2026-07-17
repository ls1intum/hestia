import { useState, useCallback, useRef, useEffect } from "react";
import { Loader2, Moon, Sun, GraduationCap, ArrowLeft } from "lucide-react";
import WorkshopFormStep1 from "@/components/WorkshopFormStep1";
import WorkshopFormStep2 from "@/components/WorkshopFormStep2";
import WorkshopGoalEntry from "@/components/WorkshopGoalEntry";
import WorkshopGeneratedTimetable from "@/components/WorkshopGeneratedTimetable";

import WorkshopPreparation from "@/components/WorkshopPreparation";
import SessionsDashboard from "@/components/SessionsDashboard";
import { Toaster } from "@/components/ui/toaster";
import { Button } from "@/components/ui/button";
import hestiaLogoLight from "@/assets/logos/wordmark-light.svg";
import hestiaLogoDark from "@/assets/logos/wordmark-dark.svg";
import { generateSession, getSessionDetail, saveDraft, finishSession } from "@/lib/api";
import { toast } from "@/hooks/use-toast";
import type {
  WorkshopInput,
  LearningGoalPlan,
  WorkshopSession,
  SessionSkeleton,
  DraftState
} from "@/lib/workshop-generator";
import { generateDefaultSkeleton, SlideData } from "@/lib/workshop-generator";

import LectureSummary from "@/components/LectureSummary";
import WorkshopFinalReview from "@/components/WorkshopFinalReview";

type Step = "input-1" | "input-2" | "lecture-summary" | "goals" | "timeline" | "prepare" | "final-review";

// Helper: compute the ordered step IDs for a given entity configuration
function computeStepOrder(entityType: "SESSION" | "LECTURE", lectureId: string | null): Step[] {
  return ALL_STEPS
    .filter(s => {
      if (entityType === "LECTURE") return s.id === "input-1" || s.id === "input-2";
      if (!lectureId && s.id === "lecture-summary") return false;
      return true;
    })
    .map(s => s.id);
}

const ALL_STEPS: { id: Step; label: string }[] = [
  { id: "input-1",  label: "Setup" },
  { id: "input-2",  label: "Activities" },
  { id: "lecture-summary", label: "Review" },
  { id: "goals",    label: "Goals" },
  { id: "timeline", label: "Timetable" },
  { id: "prepare",  label: "Preparation" },
  { id: "final-review", label: "Final Review" },
];

export default function App() {
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
    setSessionId(null);
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
    setSessionId(null);
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
      setSessionId(null);
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

  const resumeSession = async (id: string) => {
    setIsLoading(true);
    try {
      const detail = await getSessionDetail(id);
      // R-2: resolve entity type and lectureId locally so we can compute the correct
      // step order immediately, without waiting for React state to flush.
      const resolvedEntityType = detail.type ?? "SESSION";
      const resolvedLectureId = detail.lectureId ?? null;

      setSessionId(detail.id);
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
      let targetStepStr = (detail.currentStep as string) ?? "input-1";
      if (resolvedEntityType === "LECTURE" && targetStepStr === "result") {
        targetStepStr = "input-2";
      }
      if (targetStepStr === "result" || targetStepStr === "skeleton") {
        if (detail.session) {
          targetStepStr = "timeline";
        } else {
          targetStepStr = "goals";
        }
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
    // B-1: guard against rapid clicks creating duplicate sessions
    if (isLoading) return;
    setIsLoading(true);
    setIsFinished(false);
    setWorkshopInput(input);
    try {
      const draft = buildDraft(input);
      const id = await persistDraft(draft, "input-2", sessionId, entityType, currentLectureId);
      if (!sessionId) setSessionId(id);
      setStep("input-2");
    } finally {
      setIsLoading(false);
    }
  };

  const handleStep2 = async (input: WorkshopInput) => {
    // B-1: guard against rapid clicks creating duplicate sessions
    if (isLoading) return;
    setIsLoading(true);
    setIsFinished(false);
    setWorkshopInput(input);
    try {
      const draft = buildDraft(input);
      const id = await persistDraft(draft, entityType === "LECTURE" ? "result" : "goals", sessionId, entityType, currentLectureId);
      if (!sessionId) setSessionId(id);
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

  const handleGoalsEntered = async (goals: LearningGoalPlan[]) => {
    if (isGeneratingRef.current) return;
    isGeneratingRef.current = true;
    setIsFinished(false);
    setRefinedGoals(goals);
    
    const updatedInput = { ...workshopInput };

    setIsLoading(true);
    try {
      const skeleton = generateDefaultSkeleton(goals, updatedInput.duration || 90);
      setCurrentSkeleton(skeleton);
      
      // We must save the draft first. If the LLM generation times out, we don't want to create an orphaned session.
      const initialDraft = buildDraft(updatedInput, goals, skeleton);
      const currentId = await persistDraft(initialDraft, "goals", sessionId, entityType, currentLectureId);
      if (!sessionId) setSessionId(currentId);
      
      const skeletonWithId: SessionSkeleton = {
        ...skeleton,
        sessionId: currentId,
      };
      const result = await generateSession(
        goals,
        updatedInput as WorkshopInput,
        skeletonWithId
      );
      if (updatedInput.title?.trim()) {
        result.title = updatedInput.title.trim();
      }
      setSession(result);
      
      const draft = buildDraft(updatedInput, goals, skeleton, result);
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
    setSessionId(null);
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

  const loadingMessages: Record<Step, { title: string; sub: string }> = {
    "input-1":  { title: "Loading…",                    sub: "" },
    "input-2":  { title: "Loading…",                    sub: "" },
    "lecture-summary": { title: "Loading…",             sub: "" },
    "goals":    { title: "Loading…",                    sub: "" },
    "timeline": { title: "Generating your session plan…", sub: "This may take a moment." },
    "prepare":  { title: "Loading…",                    sub: "" },
    "final-review": { title: "Loading…",                sub: "" },
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
          <button onClick={handleReset} className="flex items-center gap-3 hover:opacity-80 transition-opacity shrink-0">
            <ArrowLeft className="h-5 w-5 text-muted-foreground" />
            <img src={hestiaLogoLight} alt="Hestia" className="h-6 w-auto dark:hidden" />
            <img src={hestiaLogoDark} alt="Hestia" className="h-6 w-auto hidden dark:block" />
            <span className="bg-primary/20 text-primary px-3 py-0.5 rounded-full font-body font-bold text-sm">
              Workshopper
            </span>
          </button>

          <div className="hidden sm:flex items-center justify-center flex-1 gap-1.5 text-sm font-body text-muted-foreground whitespace-nowrap px-4">
            {STEPS.map((s, i) => {
              const effectiveStep = step;
              const isClickable = i <= highestStepIdx && !isLoading;
              return (
                <span key={s.id} className="flex items-center gap-1.5">
                  <button
                    onClick={() => {
                      if (!isClickable) return;
                      // M-2: cancel any pending draft debounce before navigating
                      if (draftSaveTimeout.current) {
                        clearTimeout(draftSaveTimeout.current);
                        draftSaveTimeout.current = null;
                      }
                      // N-2: warn before navigating back past the timeline step
                      if (step === "timeline" && STEP_ORDER.indexOf(s.id) < STEP_ORDER.indexOf("timeline")) {
                        setShowTimelineBackConfirm(true);
                        return;
                      }
                      setStep(s.id);
                    }}
                    disabled={!isClickable && effectiveStep !== s.id}
                    className={`flex items-center gap-1.5 transition-opacity ${
                      isClickable ? "cursor-pointer hover:opacity-70" 
                      : i > highestStepIdx ? "cursor-not-allowed opacity-50" 
                      : "cursor-default"
                    }`}
                  >
                    <span className={`w-6 h-6 rounded-full flex items-center justify-center font-semibold transition-colors ${
                      effectiveStep === s.id ? "bg-primary text-primary-foreground"
                      : i <= highestStepIdx ? "bg-primary/30 text-primary"
                      : "bg-muted text-muted-foreground"
                    }`}>
                      {i + 1}
                    </span>
                    <span className={effectiveStep === s.id ? "text-foreground font-medium" : ""}>{s.label}</span>
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
          // N-4: pass onBack when in lecture-session mode so user isn't trapped on Step 1
          <WorkshopFormStep1
            onNext={handleStep1}
            isLoading={isLoading}
            initialInput={workshopInput}
            entityType={entityType}
            onBack={currentLectureId ? () => setStep("lecture-summary") : undefined}
          />
        )}

        {step === "input-2" && (
          <WorkshopFormStep2
            initialInput={workshopInput}
            onGenerate={handleStep2}
            isLoading={isLoading}
            onBack={() => setStep("input-1")}
            // N-1: incrementally save selections so they survive re-mount
            onSelectionsChange={(activities, materials) => {
              setWorkshopInput(prev => ({
                ...prev,
                selectedActivities: activities,
                availableMaterials: materials,
              }));
            }}
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



        {step === "timeline" && session && (
          <WorkshopGeneratedTimetable
            session={session}
            goals={refinedGoals}
            meta={workshopInput as WorkshopInput}
            onBack={() => {
              // N-2: show confirmation before going back to goals (which triggers regen)
              setShowTimelineBackConfirm(true);
            }}
            onNext={(latestSession) => {
              setSession(latestSession);
              setStep("prepare");
              persistDraft(buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, latestSession), isFinished ? "finished" : "prepare", sessionId, entityType, currentLectureId);
            }}
            onSaveSession={(updatedSession) => {
              setSession(updatedSession);
              persistDraft(buildDraft(workshopInput, refinedGoals, undefined, updatedSession), isFinished ? "finished" : "timeline", sessionId, entityType, currentLectureId);
            }}
          />
        )}

        {step === "prepare" && session && (
          <WorkshopPreparation
            session={session}
            goals={refinedGoals}
            meta={workshopInput as WorkshopInput}
            completedTasks={completedTasks}
            slidesCache={slidesCache}
            setSlidesCache={(val) => {
              setSlidesCache((prev) => {
                const nextCache = typeof val === 'function' ? val(prev) : val;
                const newSession = { ...session, slides: nextCache };
                setSession(newSession);
                // Background save of the draft so slides are persisted
                persistDraft(buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, newSession), "prepare", sessionId, entityType, currentLectureId);
                return nextCache;
              });
            }}
            onUpdateTasks={(tasks, isAllDone) => {
              setCompletedTasks(tasks);
              let nextStep = isFinished ? "finished" : "prepare";
              if (isAllDone && !isFinished) {
                setIsFinished(true);
                nextStep = "finished";
              }
              persistDraft(buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, session, tasks), nextStep, sessionId, entityType, currentLectureId);
            }}
            onBack={() => setStep("timeline")}
            onDone={(latestTasks) => {
              if (latestTasks) setCompletedTasks(latestTasks);
              persistDraft(buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, session, latestTasks ?? completedTasks), "final-review", sessionId, entityType, currentLectureId);
              setStep("final-review");
              setHighestStepIdx(STEP_ORDER.indexOf("final-review"));
              window.scrollTo(0, 0);
            }}
          />
        )}

        {step === "final-review" && session && (
          <WorkshopFinalReview
            session={session}
            meta={workshopInput as WorkshopInput}
            goals={refinedGoals}
            slidesCache={slidesCache}
            onBack={() => setStep("prepare")}
            onDone={async () => {
              // C-2: surface an error instead of silently doing nothing
              if (!sessionId) {
                toast({ title: "Cannot finish session", description: "Session ID is missing — please go back and re-save.", variant: "destructive" });
                return;
              }
              setIsLoading(true);
              try {
                await persistDraft(buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, session, completedTasks), "finished", sessionId, entityType, currentLectureId);
                await finishSession(sessionId);
                toast({ title: "Session completed!" });
                setIsFinished(true);
                handleReset();
              } catch (e) {
                toast({ title: "Save failed", description: String(e), variant: "destructive" });
              } finally {
                setIsLoading(false);
              }
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

      {/* N-2: Confirmation dialog for navigating back from timeline (would trigger regen) */}
      {showTimelineBackConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-background/70 backdrop-blur-sm p-4">
          <div className="bg-card border border-border rounded-2xl shadow-2xl p-6 max-w-sm w-full space-y-4">
            <h3 className="font-display font-semibold text-lg text-foreground">Discard timetable edits?</h3>
            <p className="text-sm text-muted-foreground font-body">
              Going back to Goals will re-generate the session plan when you continue, discarding any edits you've made to the timetable.
            </p>
            <div className="flex justify-end gap-3 pt-2">
              <Button variant="outline" onClick={() => setShowTimelineBackConfirm(false)}>Stay here</Button>
              <Button
                variant="destructive"
                onClick={() => {
                  setShowTimelineBackConfirm(false);
                  setStep("goals");
                }}
              >
                Go back anyway
              </Button>
            </div>
          </div>
        </div>
      )}

      <Toaster />
    </div>
  );
}
