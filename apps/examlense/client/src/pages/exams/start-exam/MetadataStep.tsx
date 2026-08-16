import { Input } from "@/components/ui/input";
import { Field } from "./Field";

interface Props {
  title: string;
  onTitleChange: (title: string) => void;
  /** Advance the wizard on Enter within the title field. */
  onSubmit: () => void;
}

/** First step of the from-scratch flow: name the exam. */
export const MetadataStep = ({ title, onTitleChange, onSubmit }: Props) => {
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit();
      }}
      className="space-y-hestia-3"
    >
      <Field label="Exam Title">
        <Input
          value={title}
          onChange={(e) => onTitleChange(e.target.value)}
          placeholder="e.g. Algorithms — Final Exam"
          autoFocus
        />
      </Field>
      {/* Hidden submit lets Enter advance the step via the shell's primary button. */}
      <button type="submit" className="hidden" aria-hidden tabIndex={-1} />
    </form>
  );
};
