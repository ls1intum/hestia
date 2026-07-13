import { useTheme } from "@/hooks/use-theme";
import wordmarkLight from "@/assets/hestia-wordmark-light.svg";
import wordmarkDark from "@/assets/hestia-wordmark-dark.svg";

export function HestiaWordmark({ className }: { className?: string }) {
  const { theme } = useTheme();
  return (
    <img
      src={theme === "dark" ? wordmarkDark : wordmarkLight}
      alt="Hestia"
      className={className}
    />
  );
}
