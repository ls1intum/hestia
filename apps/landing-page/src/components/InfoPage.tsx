import { ArrowRightIcon } from "@/components/icons";
import { useI18n } from "@/hooks/use-language";

export function InfoPage() {
  const { t } = useI18n();

  return (
    <section className="py-[72px]">
      <div className="mx-auto max-w-[760px] px-6">
        <a
          href="#/"
          className="inline-flex items-center gap-1.5 text-sm font-semibold text-hestia-primary hover:text-hestia-primary-hover"
        >
          <ArrowRightIcon size={14} className="rotate-180" />
          {t.info.backToHome}
        </a>
        <h1 className="mt-6 text-[clamp(28px,3.5vw,42px)] leading-[1.1] tracking-[-0.01em]">
          {t.info.title}
        </h1>
        <div className="mt-8 flex flex-col gap-6">
          <p className="whitespace-pre-line text-base leading-relaxed text-hestia-text-muted">
            {t.info.p1}
          </p>
          <p className="whitespace-pre-line text-base leading-relaxed text-hestia-text-muted">
            {t.info.p2}
          </p>
          <p className="whitespace-pre-line text-base leading-relaxed text-hestia-text-muted">
            {t.info.p3}
          </p>
        </div>
      </div>
    </section>
  );
}
