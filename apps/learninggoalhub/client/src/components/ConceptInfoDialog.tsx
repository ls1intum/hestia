import { useEffect } from "react";

/** Explains why the course page separates extracted learning goals from synthesised skills. */
export default function ConceptInfoDialog({
  onClose,
}: {
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="concept-info-title"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className="flex max-h-[85vh] w-full max-w-2xl flex-col gap-4 overflow-y-auto rounded-xl border border-hestia-border bg-hestia-surface p-6 shadow-xl"
      >
        <h2 id="concept-info-title" className="text-xl text-hestia-text">
          Learning goals &amp; skills
        </h2>

        <div className="flex flex-wrap items-center gap-2 text-xs text-hestia-text-muted">
          <span className="rounded-full border border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_4%,transparent)] px-3 py-1">
            <b className="font-semibold text-hestia-text">
              Uploaded materials
            </b>
          </span>
          <span>→ extracted →</span>
          <span className="rounded-full border border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_4%,transparent)] px-3 py-1">
            <b className="font-semibold text-hestia-text">Learning goals</b> ·
            per session
          </span>
          <span>→ synthesised →</span>
          <span className="rounded-full border border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_4%,transparent)] px-3 py-1">
            <b className="font-semibold text-hestia-text">Skills</b> · per
            course
          </span>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <section className="flex flex-col gap-1.5 text-sm leading-relaxed">
            <h3 className="font-body text-xs font-semibold uppercase tracking-wide text-hestia-accent">
              Learning goals
            </h3>
            <p className="text-hestia-text-muted">
              One goal per thing a session teaches, extracted from the uploaded
              materials. Each carries its sources and a Bloom / SOLO level. This
              is where you review, edit and approve.
            </p>
          </section>
          <section className="flex flex-col gap-1.5 text-sm leading-relaxed">
            <h3 className="font-body text-xs font-semibold uppercase tracking-wide text-hestia-primary">
              Skills
            </h3>
            <p className="text-hestia-text-muted">
              A compact hierarchy — skill → sub-skill → knowledge — synthesised
              from the learning goals. It shows what the course builds towards,
              rather than what each session covers.
            </p>
          </section>
        </div>

        <p className="border-t border-hestia-border pt-3 text-sm text-hestia-text-muted">
          Skills are created once during extraction and reviewed in the Skills
          view.
        </p>

        <button
          type="button"
          onClick={onClose}
          className="self-end rounded-md border border-hestia-border px-3 py-1.5 text-sm font-medium text-hestia-text-muted transition hover:border-hestia-text-muted hover:text-hestia-text"
        >
          Close
        </button>
      </div>
    </div>
  );
}
