import { cn } from "@/lib/utils";
import { solverModelMeta } from "@/lib/solver-model-meta";

interface Props {
  modelId: string;
  className?: string;
}

/**
 * Provider logo for a solver model (reused by the create-exam picker and the
 * results overview). Falls back to the first letter of the model name when no
 * logo is mapped. Callers own the surrounding chip/container.
 */
export const ModelLogo = ({ modelId, className }: Props) => {
  const meta = solverModelMeta(modelId);

  if (meta?.logoSrc) {
    return (
      <img
        src={meta.logoSrc}
        alt=""
        aria-hidden="true"
        loading="lazy"
        referrerPolicy="no-referrer"
        className={cn(
          "max-h-full max-w-full object-contain",
          // The OpenAI mark is black; invert it in dark mode so it stays legible.
          modelId === "gpt-5.5" ? "dark:brightness-0 dark:invert" : "",
          className,
        )}
      />
    );
  }

  return (
    <span className="text-xs font-bold text-hestia-primary">
      {(meta?.name ?? modelId).slice(0, 1)}
    </span>
  );
};
