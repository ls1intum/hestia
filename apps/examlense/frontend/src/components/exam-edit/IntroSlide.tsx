import { FileText } from "lucide-react";
import { StepGuide, PARSED_STEPS } from "./IntroStepGuide";

export const IntroSlide = () => {
  return (
    <section className="rounded-hestia-lg border border-hestia-border bg-hestia-surface p-hestia-4 shadow-hestia-sm">
      <div className="flex flex-col gap-hestia-3 md:flex-row md:items-start md:justify-between">
        <div className="max-w-2xl">
          <div className="mb-hestia-2 inline-flex h-10 w-10 items-center justify-center rounded-hestia-md bg-hestia-primary-muted text-hestia-primary">
            <FileText size={19} />
          </div>
          <p className="hestia-eyebrow text-hestia-text-muted">First review</p>
          <h2 className="mt-1 font-display text-2xl font-semibold text-hestia-text">
            Your exam was parsed
          </h2>
        </div>
      </div>

      <div className="mt-hestia-4">
        <StepGuide steps={PARSED_STEPS} density="compact" />
      </div>

    </section>
  );
};
