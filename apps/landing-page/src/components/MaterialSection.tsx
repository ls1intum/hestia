import { Button } from "@/components/ui/button";
import { ArrowRightIcon, CheckIcon } from "@/components/icons";
import { PlaceholderBadge } from "@/components/PlaceholderBadge";
import { KLAUSUR_CHECK_MAILTO, NEXTCLOUD_UPLOAD_URL } from "@/config";
import { useI18n } from "@/hooks/use-language";

export function MaterialSection() {
  const { t } = useI18n();

  return (
    <section
      id="material"
      className="scroll-mt-16 border-t border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_3%,var(--hestia-bg))] py-[88px]"
    >
      <div className="mx-auto max-w-page px-6">
        <div className="max-w-[680px]">
          <span className="font-mono text-xs uppercase tracking-[.08em] text-hestia-primary">
            {t.material.eyebrow}
          </span>
          <h2 className="mt-3.5 text-[clamp(28px,3.5vw,42px)] leading-[1.1] tracking-[-0.01em]">
            {t.material.heading}
          </h2>
          <p className="mt-4 text-pretty text-lg leading-relaxed text-hestia-text-muted">
            {t.material.intro}
          </p>
        </div>

        <div className="mt-12 grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]">
          {/* left: offer + material types */}
          <div className="flex flex-col gap-6">
            <div className="rounded-hestia-lg border border-hestia-border bg-hestia-surface p-7 shadow-hestia-sm">
              <h4 className="text-xl">{t.material.offerTitle}</h4>
              <p className="mt-3.5 text-base leading-[1.65] text-hestia-text-muted">
                {t.material.offer.pre}
                <strong className="font-semibold text-hestia-text">{t.material.offer.strong}</strong>
                {t.material.offer.post}
              </p>
              <p className="mt-4 flex flex-wrap items-center gap-2">
                <a
                  href={KLAUSUR_CHECK_MAILTO}
                  className="inline-flex items-center gap-1.5 text-base font-semibold text-hestia-primary hover:text-hestia-primary-hover"
                >
                  {t.material.offerCta}
                  <ArrowRightIcon size={16} />
                </a>
              </p>
            </div>

            <div className="rounded-hestia-lg border border-hestia-border bg-hestia-surface p-7 shadow-hestia-sm">
              <h4 className="text-xl">{t.material.materialsTitle}</h4>
              <p className="mt-3.5 text-base leading-[1.65] text-hestia-text-muted">
                {t.material.materialsBody}
              </p>
            </div>
          </div>

          {/* right: trust block (visible before upload) */}
          <div className="rounded-hestia-lg border border-hestia-border-strong bg-hestia-surface p-7 shadow-hestia-sm">
            <h4 className="mb-1 text-lg">{t.material.trustTitle}</h4>
            <p className="mb-4 text-sm text-hestia-text-muted">{t.material.trustSubtitle}</p>
            <div className="flex flex-col gap-4">
              {t.material.trustPoints.map((point) => (
                <div key={point.title} className="flex gap-3">
                  <span className="mt-px shrink-0 text-hestia-primary">
                    <CheckIcon size={20} />
                  </span>
                  <div>
                    <div className="text-base font-semibold">{point.title}</div>
                    <div className="text-sm leading-normal text-hestia-text-muted">
                      {point.description}
                    </div>
                  </div>
                </div>
              ))}
            </div>
            <div className="mt-5 border-t border-hestia-border pt-4 text-sm leading-normal text-hestia-text-muted">
              {t.material.dataConcept.pre}
              <a href="#" className="font-semibold text-hestia-primary hover:text-hestia-primary-hover">
                {t.material.dataConcept.link}
              </a>{" "}
              <PlaceholderBadge label="Text" />
            </div>
          </div>
        </div>

        {/* single CTA -> Nextcloud (secondary; newsletter stays primary) */}
        <div className="mt-10 flex flex-col items-center gap-2.5 text-center">
          <Button
            variant="secondary"
            size="lg"
            onClick={() => window.open(NEXTCLOUD_UPLOAD_URL, "_blank", "noopener")}
          >
            {t.material.uploadCta}
          </Button>
          <p className="text-sm text-hestia-text-muted">
            {t.material.uploadNote}
          </p>
        </div>
      </div>
    </section>
  );
}
