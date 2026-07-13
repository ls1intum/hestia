import { NewsletterForm } from "@/components/NewsletterForm";
import { useI18n } from "@/hooks/use-language";

export function Hero() {
  const { t } = useI18n();

  return (
    <section id="top" className="pb-20 pt-[88px]">
      <div className="mx-auto max-w-[760px] px-6 text-center">
        <h1 className="text-balance text-[clamp(40px,6vw,66px)] leading-[1.04] tracking-[-0.01em]">
          {t.hero.title}
        </h1>
        <p className="mx-auto mt-6 max-w-[620px] text-pretty text-[clamp(18px,2.2vw,21px)] leading-[1.55] text-hestia-text-muted">
          {t.hero.subtitle}
        </p>

        <div
          id="newsletter"
          className="mx-auto mt-10 max-w-[540px] scroll-mt-[88px] rounded-hestia-xl border border-hestia-border bg-hestia-surface p-6 text-left shadow-hestia-sm"
        >
          <h2 className="mb-3 font-body text-base font-semibold">{t.hero.formTitle}</h2>
          <NewsletterForm variant="hero" />
        </div>
      </div>
    </section>
  );
}
