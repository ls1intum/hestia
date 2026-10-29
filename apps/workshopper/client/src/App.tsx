import { useState, useEffect } from "react";
import { Loader2, Moon, Sun, ArrowLeft, ArrowRight } from "lucide-react";
import WorkshopFormStep1 from "@/components/WorkshopFormStep1";
import WorkshopFormStep2 from "@/components/WorkshopFormStep2";
import WorkshopFormStep2b from "@/components/WorkshopFormStep2b";
import WorkshopGoalEntry from "@/components/WorkshopGoalEntry";
import WorkshopGeneratedTimetable from "@/components/WorkshopGeneratedTimetable";
import { SlideWorkstation } from "@/components/SlideWorkstation";
import SessionsDashboard from "@/components/SessionsDashboard";
import CourseSummary from "@/components/CourseSummary";
import CourseTemplateStep from "@/components/CourseTemplateStep";
import WorkshopFinalReview from "@/components/WorkshopFinalReview";
import { Toaster } from "@/components/ui/toaster";
import { Button } from "@/components/ui/button";
import hestiaLogoLight from "@/assets/logos/wordmark-light.svg";
import hestiaLogoDark from "@/assets/logos/wordmark-dark.svg";
import { finishSession, saveSlides, errorMessage, handleAuthError } from "@/lib/api";
import { remapSlidesToBlocks } from "@/hooks/useSlideDeck";
import { toast } from "@/hooks/use-toast";
import type { WorkshopInput } from "@/lib/workshop-generator";
import { useWizardState, type Step } from "@/hooks/useWizardState";

/** The settings forms; their values are kept while moving between them. */
const FORM_STEPS: Step[] = ["input-1", "input-2", "input-2b"];

export default function App() {
  const wizard = useWizardState();
  /** The step to open once the user confirms leaving an edited timetable. */
  const [timelineBackTarget, setTimelineBackTarget] = useState<Step>("goals");
  /** Open while asking about unsaved settings on the form steps. */
  const [settingsLeaveDialog, setSettingsLeaveDialog] = useState<{ proceed: () => void } | null>(null);
  const [isSavingSettings, setIsSavingSettings] = useState(false);
  const [isSlideWorkstationOpen, setIsSlideWorkstationOpen] = useState(true);
  const {
    view, setView, step, setStep, highestStepIdx, setHighestStepIdx,
    darkMode, toggleDark, isLoading, setIsLoading,
    sessionId, session, workshopInput, refinedGoals, currentSkeleton, slidesCache, completedTasks,
    STEP_ORDER, entityType, isFinished,
    handleStep1, handleStep2Activities, handleStep2, handleCourseFinish, handleGoalsEntered,
    startNewSession, startNewCourse, startSessionFromCourse, resumeSession, persistDraft,
    setSlidesCache, setWorkshopInput, setCompletedTasks,
    setSession, setIsFinished, showTimelineBackConfirm, setShowTimelineBackConfirm, STEPS,
    originalSession, currentCourseId, buildDraft, flushDraft, leaveGuardRef, confirmLeave, keptGoals, keepGoalDraft,
    hasUnsavedSettings, saveSettings, discardSettings,
    templateInfo, setTemplateInfo
  } = wizard;

  // The form steps share one guard: leaving them with unsaved settings asks first
  useEffect(() => {
    if (view !== "wizard" || !FORM_STEPS.includes(step)) return;
    leaveGuardRef.current = {
      isDirty: hasUnsavedSettings,
      confirmLeave: (proceed) => setSettingsLeaveDialog({ proceed }),
    };
    return () => { leaveGuardRef.current = null; };
  });

  // The timetable counts as edited once its title or blocks differ from what was generated
  const timetableEdited = !!(session && originalSession) &&
    JSON.stringify([session.title, session.blocks]) !== JSON.stringify([originalSession.title, originalSession.blocks]);

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
          onNewCourse={startNewCourse}
          onNewSessionFromCourse={startSessionFromCourse}
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
          <button onClick={() => confirmLeave(() => { flushDraft(); setView("dashboard"); })} className="flex items-center gap-3 hover:opacity-80 transition-opacity shrink-0">
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
                      if (!isClickable || s.id === step) return;
                      // Moving between the form steps keeps their values, so only leaving them asks
                      const withinForms = FORM_STEPS.includes(step) && FORM_STEPS.includes(s.id);
                      // Unsaved input on this step (e.g. a timetable block in edit mode) is resolved first
                      (withinForms ? (go: () => void) => go() : confirmLeave)(() => {
                        // M-2: send any waiting draft save now rather than dropping it
                        flushDraft();
                        // N-2: warn before navigating back past an edited timetable
                        if (step === "timeline" && STEP_ORDER.indexOf(s.id) < STEP_ORDER.indexOf("timeline") && timetableEdited) {
                          setTimelineBackTarget(s.id);
                          setShowTimelineBackConfirm(true);
                          return;
                        }
                        setStep(s.id);
                      });
                    }}
                    disabled={!isClickable && effectiveStep !== s.id}
                    className={`flex items-center gap-1.5 transition-opacity ${isClickable ? "cursor-pointer hover:opacity-70"
                      : i > highestStepIdx ? "cursor-not-allowed opacity-50"
                        : "cursor-default"
                      }`}
                  >
                    <span className={`w-6 h-6 rounded-full flex items-center justify-center font-semibold transition-colors ${effectiveStep === s.id ? "bg-primary text-primary-foreground"
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
          // N-4: pass onBack when in course-session mode so user isn't trapped on Step 1
          <WorkshopFormStep1
            onNext={handleStep1}
            isLoading={isLoading}
            initialInput={workshopInput}
            entityType={entityType}
            onBack={currentCourseId ? () => confirmLeave(() => setStep("course-summary")) : undefined}
            onChange={(values) => setWorkshopInput(prev => ({ ...prev, ...values }))}
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

        {step === "course-template" && entityType === "COURSE" && (
          <CourseTemplateStep
            courseId={sessionId}
            template={templateInfo}
            onTemplateChange={setTemplateInfo}
            onBack={() => setStep("input-2b")}
            onFinish={handleCourseFinish}
            isLoading={isLoading}
          />
        )}

        {step === "course-summary" && currentCourseId && (
          <CourseSummary
            courseTemplate={templateInfo}
            settings={workshopInput}
            onEdit={() => setStep("input-1")}
            onTitleChange={(title) => setWorkshopInput(prev => ({ ...prev, title }))}
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
            onBack={() => { flushDraft(); setStep(currentCourseId ? "course-summary" : "input-2b"); }}
            onContinue={handleGoalsEntered}
            isLoading={isLoading}
            initialGoals={refinedGoals}
            keptGoals={keptGoals}
            onKeepDraft={keepGoalDraft}
            registerLeaveGuard={(guard) => { leaveGuardRef.current = guard; }}
          />
        )}

        {step === "timeline" && session && (
          <WorkshopGeneratedTimetable
            session={session}
            registerLeaveGuard={(guard) => { leaveGuardRef.current = guard; }}
            goals={refinedGoals}
            meta={workshopInput as WorkshopInput}
            onMetaChanged={(newMeta: WorkshopInput) => setWorkshopInput(newMeta)}
            onBack={() => {
              if (timetableEdited) {
                setTimelineBackTarget("goals");
                setShowTimelineBackConfirm(true);
              } else {
                setStep("goals");
              }
            }}
            onNext={(latestSession) => {
              setSession(latestSession);
              setStep("prepare");
              persistDraft(buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, latestSession), isFinished ? "finished" : "prepare", sessionId, currentCourseId);
            }}
            onSaveSession={(updated) => {
              // Slides are stored by block position: keep them with their blocks
              const slides = session ? remapSlidesToBlocks(session.blocks, updated.blocks, slidesCache) : null;
              const updatedSession = slides ? { ...updated, slides } : updated;
              if (slides) {
                setSlidesCache(slides);
                if (sessionId) saveSlides(sessionId, slides).catch(e => {
                  if (!handleAuthError(e)) toast({ title: "Slides could not be re-ordered", description: errorMessage(e, "Please regenerate them in Preparation."), variant: "destructive" });
                });
              }
              setSession(updatedSession);
              persistDraft(buildDraft(workshopInput, refinedGoals, undefined, updatedSession), isFinished ? "finished" : "timeline", sessionId, currentCourseId);
            }}
          />
        )}

        {step === "prepare" && session && (
          <div className="space-y-6">
            <div>
              <h2 className="font-display font-semibold text-2xl">Slide Preparation</h2>
              <p className="text-muted-foreground font-body text-sm mt-1">Generate and edit the slides. Use the task list to help you prepare.</p>
            </div>
            <SlideWorkstation
              session={session}
              meta={workshopInput as WorkshopInput}
              goals={refinedGoals}
              templateInfo={templateInfo}
              setTemplateInfo={setTemplateInfo}
              registerLeaveGuard={(guard) => { leaveGuardRef.current = guard; }}
              isOpen={isSlideWorkstationOpen}
              setIsOpen={setIsSlideWorkstationOpen}
              slidesCache={slidesCache}
              setSlidesCache={(val) => {
                setSlidesCache((prev) => {
                  const nextCache = typeof val === 'function' ? val(prev) : val;
                  const newSession = { ...session, slides: nextCache };
                  setSession(newSession);
                  // Background save of the draft so slides are persisted
                  persistDraft(buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, newSession), isFinished ? "finished" : "prepare", sessionId, currentCourseId);
                  return nextCache;
                });
              }}
              completedTasks={completedTasks}
              onCompletedTasksChange={(tasks) => {
                setCompletedTasks(tasks);
                persistDraft(buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, session, tasks), isFinished ? "finished" : "prepare", sessionId, currentCourseId);
              }}
            />
            <div className="mt-8 flex justify-between items-center bg-surface p-4 rounded-xl border shadow-sm">
              <Button variant="outline" onClick={() => confirmLeave(() => setStep("timeline"))} className="gap-2 font-body shrink-0 bg-transparent border-[1.5px] border-primary text-primary hover:bg-primary/5 transition-all duration-150">
                <ArrowLeft className="h-4 w-4" /> Timetable
              </Button>
              <Button
                onClick={() => confirmLeave(() => {
                  persistDraft(buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, session, completedTasks), isFinished ? "finished" : "final-review", sessionId, currentCourseId);
                  setStep("final-review");
                  setHighestStepIdx(STEP_ORDER.indexOf("final-review"));
                  window.scrollTo(0, 0);
                })}
                className="gap-2 shadow-md hover:shadow-lg bg-primary text-primary-foreground font-semibold shrink-0 rounded-lg transition-all duration-150"
              >
                Review & Export <ArrowRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
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
                const saved = await persistDraft(buildDraft(workshopInput, refinedGoals, currentSkeleton ?? undefined, session, completedTasks), "finished", sessionId, currentCourseId);
                if (!saved) return; // the reason was shown
                await finishSession(sessionId);
                toast({ title: "Session completed!" });
                setIsFinished(true);
                setView("dashboard");
              } catch (e) {
                if (!handleAuthError(e)) toast({ title: "Save failed", description: errorMessage(e, "Please try again."), variant: "destructive" });
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
            <p className="font-body font-medium text-foreground">Loading…</p>
          </div>
        </div>
      )}

      {/* Unsaved settings on the form steps */}
      {settingsLeaveDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-background/70 backdrop-blur-sm p-4">
          <div className="bg-card border border-border rounded-2xl shadow-2xl p-6 max-w-sm w-full space-y-4">
            <h3 className="font-display font-semibold text-lg text-foreground">Your settings are not saved</h3>
            <p className="text-sm text-muted-foreground font-body">
              You changed the {entityType === "COURSE" ? "course" : "session"} settings. Save them before you continue, or discard the changes.
            </p>
            <div className="flex justify-end gap-2 pt-2 flex-wrap">
              <Button variant="outline" disabled={isSavingSettings} onClick={() => setSettingsLeaveDialog(null)}>Stay</Button>
              <Button
                variant="outline"
                disabled={isSavingSettings}
                className="text-destructive hover:text-destructive"
                onClick={() => {
                  const proceed = settingsLeaveDialog.proceed;
                  setSettingsLeaveDialog(null);
                  discardSettings();
                  proceed();
                }}
              >
                Discard changes
              </Button>
              <Button
                disabled={isSavingSettings}
                onClick={async () => {
                  const proceed = settingsLeaveDialog.proceed;
                  setIsSavingSettings(true);
                  let saved = false;
                  try {
                    saved = await saveSettings();
                  } finally {
                    setIsSavingSettings(false);
                  }
                  if (!saved) return; // keep the dialog; the reason was shown
                  setSettingsLeaveDialog(null);
                  proceed();
                }}
              >
                {isSavingSettings ? <><Loader2 className="h-4 w-4 mr-2 animate-spin" /> Saving…</> : "Save and continue"}
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* N-2: Confirmation dialog for navigating back from timeline (would trigger regen) */}
      {showTimelineBackConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-background/70 backdrop-blur-sm p-4">
          <div className="bg-card border border-border rounded-2xl shadow-2xl p-6 max-w-sm w-full space-y-4">
            <h3 className="font-display font-semibold text-lg text-foreground">Leave the edited timetable?</h3>
            <p className="text-sm text-muted-foreground font-body">
              Your timetable edits are saved and stay when you come back. But continuing from the Goals step
              generates a new timetable, which replaces your edits.
            </p>
            <div className="flex justify-end gap-3 pt-2">
              <Button variant="outline" onClick={() => setShowTimelineBackConfirm(false)}>Stay here</Button>
              <Button
                variant="destructive"
                onClick={() => {
                  setShowTimelineBackConfirm(false);
                  setStep(timelineBackTarget);
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
