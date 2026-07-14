import { Moon, Sun } from "lucide-react";
import { useTheme } from "@/hooks/ui/use-theme";

export const ThemeToggle = () => {
  const { theme, toggle } = useTheme();
  const isDark = theme === "dark";
  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={isDark ? "Switch to light mode" : "Switch to dark mode"}
      aria-pressed={isDark}
      className="inline-flex h-10 w-10 items-center justify-center rounded-hestia-md text-hestia-text transition-colors hover:bg-hestia-primary-muted"
    >
      {isDark ? <Sun size={18} /> : <Moon size={18} />}
    </button>
  );
};
