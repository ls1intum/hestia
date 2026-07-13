import { ReactNode, useState } from "react";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

interface Props {
  label: string;
  value: string | null;
  emptyLabel: string;
  formatter?: (v: string) => string;
  children: (close: () => void) => ReactNode;
}

export const MetaChip = ({ label, value, emptyLabel, formatter, children }: Props) => {
  const [open, setOpen] = useState(false);
  const display = value ? (formatter ? formatter(value) : value) : null;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          className={`inline-flex items-center gap-1 rounded-hestia-full border px-hestia-3 py-1 text-xs transition-colors ${
            display
              ? "border-hestia-border bg-hestia-surface text-hestia-text hover:border-hestia-primary"
              : "border-dashed border-hestia-border bg-transparent text-hestia-text-muted hover:text-hestia-text"
          }`}
        >
          {display ? (
            <>
              <span className="text-hestia-text-muted">{label} ·</span>
              <span className="font-medium">{display}</span>
            </>
          ) : (
            emptyLabel
          )}
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-64">{children(() => setOpen(false))}</PopoverContent>
    </Popover>
  );
};