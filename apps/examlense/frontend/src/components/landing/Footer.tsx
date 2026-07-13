import { HestiaMark } from "@/components/HestiaMark";

export const Footer = () => {
  return (
    <footer
      className="fixed bottom-0 left-0 right-0 z-40 h-16 border-t bg-hestia-bg"
      style={{ borderColor: "hsl(var(--hestia-border))" }}
    >
      <div className="mx-auto flex h-full w-full max-w-[1120px] items-center justify-between gap-hestia-4 px-hestia-5">
        <div className="flex items-center gap-hestia-3">
          <HestiaMark size={20} />
          <span className="text-sm text-hestia-text">
            ExamLense · part of HESTIA
          </span>
        </div>
      </div>
    </footer>
  );
};
