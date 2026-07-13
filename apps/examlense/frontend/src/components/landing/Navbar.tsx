import { useEffect, useState } from "react";
import { HestiaMark } from "@/components/HestiaMark";
import { ThemeToggle } from "@/components/ThemeToggle";

export const Navbar = () => {
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
    <header
      className={`sticky top-0 z-50 w-full border-b bg-hestia-surface transition-shadow duration-150 ${
        scrolled ? "shadow-hestia-sm" : ""
      }`}
      style={{ borderColor: "hsl(var(--hestia-border))" }}
    >
      <div className="mx-auto flex h-16 w-full max-w-[1120px] items-center justify-between px-hestia-5">
        <div className="flex items-center gap-hestia-3">
          <HestiaMark size={32} />
          <span className="font-display text-2xl font-semibold text-hestia-text leading-none">
            ExamLense
          </span>
        </div>

        <nav className="flex items-center gap-hestia-3">
          <ThemeToggle />
        </nav>
      </div>
    </header>
  );
};
