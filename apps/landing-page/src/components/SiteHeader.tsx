import { HestiaWordmark } from "@/components/HestiaWordmark";
import { ThemeToggle } from "@/components/ThemeToggle";
import { useI18n, type Language } from "@/hooks/use-language";

const LANGUAGES: { code: Language; label: string }[] = [
  { code: "de", label: "DE" },
  { code: "en", label: "EN" },
];

export function SiteHeader() {
  const { t, language, setLanguage } = useI18n();

  return (
    <header className="sticky top-0 z-30 border-b border-hestia-border bg-hestia-bg">
      <div className="mx-auto flex h-16 max-w-page items-center justify-between px-6">
        <a href="#top" className="flex items-center">
          <HestiaWordmark className="block h-[26px]" />
        </a>
        <nav className="flex items-center gap-4 sm:gap-6">
          <a href="#newsletter" className="text-sm font-semibold text-hestia-primary hover:text-hestia-primary-hover">
            {t.header.newsletter}
          </a>
          <a href="#material" className="text-sm font-medium text-hestia-text-muted hover:text-hestia-text">
            {t.header.donate}
          </a>
          <div
            role="group"
            aria-label={t.header.languageGroup}
            className="flex items-center gap-0.5 rounded-full border border-hestia-border p-0.5"
          >
            {LANGUAGES.map(({ code, label }) => {
              const active = language === code;
              return (
                <button
                  key={code}
                  type="button"
                  onClick={() => setLanguage(code)}
                  aria-pressed={active}
                  className={`rounded-full px-2 py-0.5 font-mono text-[11px] transition-colors ${
                    active
                      ? "bg-hestia-primary-muted font-semibold text-hestia-primary"
                      : "text-hestia-text-muted hover:text-hestia-text"
                  }`}
                >
                  {label}
                </button>
              );
            })}
          </div>
          <ThemeToggle />
        </nav>
      </div>
    </header>
  );
}
