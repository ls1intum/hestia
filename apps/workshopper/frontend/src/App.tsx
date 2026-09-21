import { useState, useCallback, useRef, useEffect } from "react";
import { Loader2, Moon, Sun, GraduationCap, ArrowLeft } from "lucide-react";
import WorkshopFormStep1 from "@/components/WorkshopFormStep1";
import WorkshopFormStep2 from "@/components/WorkshopFormStep2";
import WorkshopFormStep2b from "@/components/WorkshopFormStep2b";
import WorkshopGoalEntry from "@/components/WorkshopGoalEntry";

import WorkshopGeneratedTimetable from "@/components/WorkshopGeneratedTimetable";

import WorkshopPreparation from "@/components/WorkshopPreparation";
import SessionsDashboard from "@/components/SessionsDashboard";
import { Toaster } from "@/components/ui/toaster";
import { Button } from "@/components/ui/button";
import hestiaLogoLight from "@/assets/logos/wordmark-light.svg";
import hestiaLogoDark from "@/assets/logos/wordmark-dark.svg";
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

import LectureSummary from "@/components/LectureSummary";
import WorkshopFinalReview from "@/components/WorkshopFinalReview";

type Step = "input-1" | "input-2" | "input-2b" | "lecture-summary" | "goals" | "timeline" | "prepare" | "final-review";

// Helper: compute the ordered step IDs for a given entity configuration
function computeStepOrder(entityType: "SESSION" | "LECTURE", lectureId: string | null): Step[] {
  return ALL_STEPS
    .filter(s => {
      if (entityType === "LECTURE") return s.id === "input-1" || s.id === "input-2" || s.id === "input-2b";
      if (!lectureId && s.id === "lecture-summary") return false;
      return true;
    })
    .map(s => s.id);
}

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

import { useWizardState } from "@/hooks/useWizardState";

export default function App() {
  const wizard = useWizardState();
  const {
    view, setView, step, setStep, highestStepIdx, setHighestStepIdx,
    darkMode, toggleDark, isLoading, setIsLoading,
    sessionId, session, workshopInput, refinedGoals, currentSkeleton, slidesCache, completedTasks,
    STEP_ORDER, currentIdx, entityType, isFinished,
    handleStep1, handleStep2Activities, handleStep2, handleGoalsEntered,
    handleReset, startNewSession, startNewLecture, startSessionFromLecture, resumeSession, persistDraft,
    setSlidesCache, setWorkshopInput, setRefinedGoals, setCurrentSkeleton, setCompletedTasks,
    setSession, setIsFinished, showTimelineBackConfirm, setShowTimelineBackConfirm, STEPS,
    originalSession, currentLectureId, buildDraft, draftSaveTimeout, loadingMessages
  } = wizard;

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
          <button onClick={() => setView("dashboard")} className="flex items-center gap-3 hover:opacity-80 transition-opacity shrink-0">
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
            onNext={handleStep2Activities}
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

        {step === "input-2b" && (
          <WorkshopFormStep2b
            initialInput={workshopInput}
            onGenerate={handleStep2}
            isLoading={isLoading}
            onBack={() => setStep("input-2")}
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
            onBack={() => currentLectureId ? setStep("lecture-summary") : setStep("input-2b")}
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
            onGoalsChanged={(newGoals: LearningGoalPlan[]) => setRefinedGoals(newGoals)}
            onMetaChanged={(newMeta: WorkshopInput) => setWorkshopInput(newMeta)}
            onBack={() => {
              // Check if session differs from original
              if (originalSession && JSON.stringify(session) !== JSON.stringify(originalSession)) {
                setShowTimelineBackConfirm(true);
              } else {
                setStep("goals");
              }
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
                setView("dashboard");
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
