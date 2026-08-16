import { ThemeToggle } from "@/components/shared/ThemeToggle";
import { HelpDialog } from "./HelpDialog";

interface Props {
  /** When set, renders an info button that opens the mode-specific help guide. */
  helpVariant?: "edit" | "grading";
}

/** Shared footer utility icons used by both edit and grading chrome. */
export const ChromeUtilityCluster = ({ helpVariant }: Props) => (
  <div className="flex items-center gap-1">
    {helpVariant && <HelpDialog variant={helpVariant} />}
    <ThemeToggle />
  </div>
);
