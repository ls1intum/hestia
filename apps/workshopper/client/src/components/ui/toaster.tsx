import { useToast } from "@/hooks/use-toast";
import { cn } from "@/lib/utils";
import { X } from "lucide-react";

export function Toaster() {
  const { toasts, dismiss } = useToast();

  return (
    <div
      aria-live="polite"
      className="fixed bottom-4 right-4 z-50 flex flex-col gap-2 w-full max-w-sm pointer-events-none"
    >
      {toasts.map((t) => (
        <div
          key={t.id}
          className={cn(
            "pointer-events-auto flex items-start gap-3 rounded-lg border p-4 shadow-lg backdrop-blur-sm transition-all animate-in slide-in-from-bottom-2",
            t.variant === "destructive"
              ? "bg-destructive text-destructive-foreground border-destructive/30"
              : "bg-card text-card-foreground border-border/60"
          )}
        >
          <div className="flex-1 min-w-0">
            <p className="text-sm font-semibold font-body">{t.title}</p>
            {t.description && (
              <p className="text-xs font-body mt-0.5 opacity-80">{t.description}</p>
            )}
          </div>
          <button
            onClick={() => dismiss(t.id)}
            className="shrink-0 opacity-60 hover:opacity-100 transition-opacity"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      ))}
    </div>
  );
}
