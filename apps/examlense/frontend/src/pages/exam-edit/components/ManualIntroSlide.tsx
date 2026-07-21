import { Sparkles } from "lucide-react";
import { StepGuide, EDIT_STEPS } from "@/components/shared/chrome/IntroStepGuide";

/**
 * First-open intro for manually-created exams. Walks the author through the
 * four authoring steps, each illustrated with a static mini-replica of the
 * real editor UI (shared with the footer HelpDialog via StepGuide).
 */
export const ManualIntroSlide = () => {
  return (
    <section className="hestia-card">
      <div className="max-w-2xl">
        <div className="mb-hestia-4 inline-flex h-12 w-12 items-center justify-center rounded-hestia-md bg-hestia-primary-muted text-hestia-primary">
          <Sparkles size={22} />
        </div>
        <p className="hestia-eyebrow text-hestia-text-muted">New Exam</p>
        <h2 className="mt-1 font-display text-3xl font-semibold text-hestia-text">
          Start building your exam
        </h2>
      </div>

      <div className="mt-hestia-6">
        <StepGuide steps={EDIT_STEPS} />
      </div>

    </section>
  );
};
