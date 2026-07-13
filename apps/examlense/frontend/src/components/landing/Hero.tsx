import { Link } from "react-router-dom";
import { ArrowRight } from "lucide-react";

const scrollToHowItWorks = () => {
  const el = document.getElementById("how-it-works");
  if (!el) return;
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  el.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "start" });
};

export const Hero = () => {
  return (
    <section className="mx-auto w-full max-w-[1120px] px-hestia-5 pt-hestia-10 pb-hestia-10 md:pt-[6rem] md:pb-hestia-10">
      <div className="max-w-3xl">
        <h1 className="text-4xl md:text-[3.5rem] font-bold leading-[1.1] text-hestia-text">
          See which parts of your exam <span className="text-hestia-primary">AI can already solve.</span>
        </h1>
        <p className="mt-hestia-5 max-w-[640px] text-lg text-hestia-text-muted leading-relaxed">
          ExamLense helps university instructors stress-test their exams against GPT and Claude. Paste an exam, review the AI-generated solutions, and get resilience metrics for every task.
        </p>
        <div className="mt-hestia-6 flex flex-wrap items-center gap-hestia-5">
          <Link
            to="/exams"
            className="inline-flex items-center gap-2 rounded-hestia-md bg-hestia-primary px-hestia-5 py-hestia-3 text-base font-semibold text-white shadow-hestia-sm transition-colors hover:bg-hestia-primary-hover"
          >
            Open your dashboard
            <ArrowRight size={18} />
          </Link>
          <button
            type="button"
            onClick={scrollToHowItWorks}
            className="inline-flex items-center gap-1 rounded-hestia-sm text-base font-medium text-hestia-primary underline-offset-4 hover:underline"
          >
            See how it works
            <ArrowRight size={16} />
          </button>
        </div>
      </div>
    </section>
  );
};
