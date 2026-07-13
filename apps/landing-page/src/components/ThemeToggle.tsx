import { useTheme } from "@/hooks/use-theme";
import { useI18n } from "@/hooks/use-language";
import { MoonIcon, SunIcon } from "@/components/icons";

export function ThemeToggle() {
  const { theme, toggleTheme } = useTheme();
  const { t } = useI18n();
  const label = theme === "dark" ? t.themeToggle.toLight : t.themeToggle.toDark;

  return (
    <button
      type="button"
      onClick={toggleTheme}
      aria-label={label}
      title={label}
      className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-hestia-border text-hestia-text-muted transition-colors duration-150 hover:text-hestia-text"
    >
      {theme === "dark" ? <SunIcon size={15} /> : <MoonIcon size={15} />}
    </button>
  );
}
