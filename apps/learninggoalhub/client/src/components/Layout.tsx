import { Link } from "react-router-dom";
import type { ReactNode } from "react";
import { useTheme } from "../theme/context.ts";
import ThemeToggle from "./ThemeToggle.tsx";
import wordmarkLight from "../assets/logos/wordmark-light.svg";
import wordmarkDark from "../assets/logos/wordmark-dark.svg";

/** App shell: HESTIA top nav (logo + theme toggle) over a centered page container. */
export default function Layout({ children }: { children: ReactNode }) {
  const { resolved } = useTheme();
  // Transparent logo variants are named for the background they sit on (light bg / dark bg).
  const wordmark = resolved === "dark" ? wordmarkDark : wordmarkLight;

  return (
    <div className="min-h-screen bg-hestia-bg text-hestia-text">
      <header className="border-b border-hestia-border bg-hestia-surface">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-3 px-6 py-3">
          <Link to="/" className="flex items-center gap-3" aria-label="LearningGoalHub home">
            <img src={wordmark} alt="HESTIA" className="h-8 w-auto" />
            <span className="rounded-full bg-hestia-primary-muted px-2 py-0.5 text-xs font-semibold text-hestia-primary">
              LearningGoalHub
            </span>
          </Link>
          <ThemeToggle />
        </div>
      </header>
      <main className="mx-auto max-w-5xl px-6 py-8">{children}</main>
    </div>
  );
}
