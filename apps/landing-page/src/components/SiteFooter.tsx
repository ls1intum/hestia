import { HestiaWordmark } from "@/components/HestiaWordmark";
import { NewsletterForm } from "@/components/NewsletterForm";
import { PlaceholderBadge } from "@/components/PlaceholderBadge";
import { useI18n } from "@/hooks/use-language";

export function SiteFooter() {
  const { t } = useI18n();

  return (
    <footer className="border-t border-hestia-border bg-[color-mix(in_srgb,var(--hestia-text)_4%,var(--hestia-bg))] pb-10 pt-[72px]">
      <div className="mx-auto max-w-page px-6">
        <div className="grid grid-cols-1 items-start gap-10 md:grid-cols-2 md:gap-16">
          <div>
            <HestiaWordmark className="block h-7" />
            <p className="mt-4 max-w-[340px] text-base leading-relaxed text-hestia-text-muted">
              {t.footer.tagline}
            </p>
          </div>
          <div>
            <h3 className="mb-3 font-body text-base font-semibold">{t.footer.stayUpdated}</h3>
            <div className="max-w-[420px]">
              <NewsletterForm variant="footer" />
            </div>
          </div>
        </div>
        <div className="mt-12 flex flex-wrap items-center justify-between gap-4 border-t border-hestia-border pt-6">
          <span className="text-sm text-hestia-text-muted">{t.footer.copyright}</span>
          <nav className="flex flex-wrap items-center gap-5">
            <a href="#/impressum" className="text-sm text-hestia-text-muted hover:text-hestia-text">
              {t.footer.imprint}
            </a>
            <span className="inline-flex items-center gap-1.5 text-sm">
              <a href="#" className="text-hestia-text-muted hover:text-hestia-text">
                {t.footer.privacy}
              </a>
              <PlaceholderBadge />
            </span>
          </nav>
        </div>
      </div>
    </footer>
  );
}
