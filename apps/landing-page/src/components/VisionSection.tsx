import { useI18n } from "@/hooks/use-language";

export function VisionSection() {
  const { t } = useI18n();

  return (
    <section className="border-t border-hestia-border py-[88px]">
      <div className="mx-auto grid max-w-page grid-cols-1 items-start gap-12 px-6 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)] lg:gap-16">
        <div className="lg:sticky lg:top-24">
          <span className="font-mono text-xs uppercase tracking-[.08em] text-hestia-primary">
            {t.vision.eyebrow}
          </span>
          <h2 className="mt-3.5 text-balance text-[clamp(28px,3.5vw,42px)] leading-[1.1] tracking-[-0.01em]">
            {t.vision.heading}
          </h2>
        </div>
        <div className="max-w-[560px]">
          <p className="text-pretty text-[19px] leading-[1.65] text-hestia-text">
            {t.vision.p1.pre}
            <strong className="font-semibold text-hestia-text">{t.vision.p1.strong}</strong>
            {t.vision.p1.post}
          </p>
          <p className="mt-5 text-pretty text-[17px] leading-[1.7] text-hestia-text-muted">
            {t.vision.p2.pre}
            <strong className="font-semibold text-hestia-text">{t.vision.p2.strong}</strong>
            {t.vision.p2.post}
          </p>
          <p className="mt-5 text-pretty text-[17px] leading-[1.7] text-hestia-text-muted">
            {t.vision.p3}
          </p>
        </div>
      </div>
    </section>
  );
}
