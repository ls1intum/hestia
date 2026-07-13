import { ReactNode } from "react";
import { ArrowRightIcon, RouteIcon, ShieldCheckIcon, TargetIcon } from "@/components/icons";
import { KLAUSUR_CHECK_MAILTO } from "@/config";
import { useI18n } from "@/hooks/use-language";

/** Non-translatable per-tool wiring, zipped with the translated copy by index. */
const toolMeta: { icon: ReactNode; ctaHref: string }[] = [
  { icon: <ShieldCheckIcon size={22} strokeWidth={1.75} />, ctaHref: KLAUSUR_CHECK_MAILTO },
  { icon: <TargetIcon size={22} strokeWidth={1.75} />, ctaHref: "#newsletter" },
  { icon: <RouteIcon size={22} strokeWidth={1.75} />, ctaHref: "#newsletter" },
];

export function PipelineSection() {
  const { t } = useI18n();
  const tools = t.pipeline.tools.map((tool, i) => ({ ...tool, ...toolMeta[i] }));

  return (
    <section className="border-t border-hestia-border py-[88px]">
      <div className="mx-auto max-w-page px-6">
        <div className="max-w-[640px]">
          <span className="font-mono text-xs uppercase tracking-[.08em] text-hestia-primary">
            {t.pipeline.eyebrow}
          </span>
          <h2 className="mt-3.5 text-[clamp(28px,3.5vw,42px)] leading-[1.1] tracking-[-0.01em]">
            {t.pipeline.heading}
          </h2>
          <p className="mt-4 text-pretty text-lg leading-relaxed text-hestia-text-muted">
            {t.pipeline.intro.pre}
            <a href="#newsletter" className="font-semibold text-hestia-primary hover:text-hestia-primary-hover">
              {t.pipeline.intro.link}
            </a>
            {t.pipeline.intro.post}
          </p>
        </div>
        <div className="mt-12 grid grid-cols-1 gap-6 md:grid-cols-3">
          {tools.map((tool) => (
            <div
              key={tool.title}
              className="flex flex-col gap-4 rounded-hestia-lg border border-hestia-border bg-hestia-surface p-7 shadow-hestia-sm"
            >
              <span className="inline-flex h-11 w-11 items-center justify-center rounded-hestia-md bg-hestia-primary-muted text-hestia-primary">
                {tool.icon}
              </span>
              <h3 className="font-body text-xl font-semibold">{tool.title}</h3>
              <p className="flex-1 text-base leading-relaxed text-hestia-text-muted">
                {tool.description}
              </p>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <span className="rounded-full border border-hestia-border px-2.5 py-px font-mono text-[11px] tracking-[.04em] text-hestia-text-muted">
                  {tool.status}
                </span>
                <a
                  href={tool.ctaHref}
                  className="inline-flex items-center gap-1.5 text-sm font-semibold text-hestia-primary hover:text-hestia-primary-hover"
                >
                  {tool.ctaLabel}
                  <ArrowRightIcon size={14} />
                </a>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
