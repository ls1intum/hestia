import { IS_PRODUCTION } from "@/config";
import { useI18n } from "@/hooks/use-language";

/**
 * Fixed marker pinned to the top-left corner so visitors always know this is a test
 * deployment. Hidden entirely on the Production build (VITE_ENVIRONMENT=production).
 * Non-interactive so it never blocks the header/logo underneath.
 */
export function TestSystemBanner() {
  const { t } = useI18n();
  if (IS_PRODUCTION) return null;

  return (
    <div
      className="pointer-events-none fixed left-0 top-0 z-50 rounded-br-hestia-md bg-hestia-primary px-3.5 py-1.5 font-mono text-[13px] font-semibold uppercase tracking-[.08em] text-hestia-text-on-primary shadow-hestia-sm"
      role="note"
    >
      {t.banner.testSystem}
    </div>
  );
}
