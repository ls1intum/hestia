import { ArrowRightIcon } from "@/components/icons";
import { useI18n } from "@/hooks/use-language";

export function ImpressumPage() {
  const { t } = useI18n();

  return (
    <section className="py-[72px]">
      <div className="mx-auto max-w-[760px] px-6">
        <a
          href="#/"
          className="inline-flex items-center gap-1.5 text-sm font-semibold text-hestia-primary hover:text-hestia-primary-hover"
        >
          <ArrowRightIcon size={14} className="rotate-180" />
          {t.imprint.backToHome}
        </a>
        <h1 className="mt-6 text-[clamp(28px,3.5vw,42px)] leading-[1.1] tracking-[-0.01em]">
          {t.imprint.title}
        </h1>
        <div className="mt-10 flex flex-col gap-8">
          {t.imprint.sections.map((sec) => (
            <div key={sec.heading}>
              <h2 className="font-body text-xl font-semibold">{sec.heading}</h2>
              <p className="mt-2.5 whitespace-pre-line text-base leading-relaxed text-hestia-text-muted">
                {sec.body}
              </p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
