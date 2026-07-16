import { Pencil } from "lucide-react";
import type { Section } from "@/lib/exam/exam-helpers";
import { useClickToEdit } from "@/hooks/ui/use-click-to-edit";

interface Props {
  section: Section;
  onPatch: (patch: Partial<Section>) => void;
}

/**
 * Editable section title used in the Exam Edit view. Click-to-edit:
 * displays the name with a pencil affordance; clicking flips to an
 * inline input that commits on blur / Enter and reverts on Escape.
 */
export const SectionTitleInput = ({ section, onPatch }: Props) => {
  const { editing, startEditing, inputProps } = useClickToEdit(
    section.name,
    (next) => {
      if (next !== section.name) onPatch({ name: next });
    },
  );

  if (editing) {
    return (
      <input
        {...inputProps}
        placeholder="Section name (e.g. Part A)"
        className="w-full bg-transparent font-body text-base font-semibold text-hestia-text placeholder:font-normal placeholder:text-hestia-text-muted/35 focus:outline-none"
      />
    );
  }

  return (
    <button
      type="button"
      onClick={startEditing}
      className="group flex max-w-full items-center gap-1.5 text-left font-body text-base font-semibold leading-tight text-hestia-text transition-colors hover:text-hestia-primary"
    >
      <span className="truncate">
        {section.name || (
          <span className="font-normal text-hestia-text-muted/60">
            Section name (e.g. Part A)
          </span>
        )}
      </span>
      <Pencil
        size={12}
        className="shrink-0 text-hestia-text-muted transition-colors group-hover:text-hestia-primary"
        aria-hidden
      />
    </button>
  );
};
