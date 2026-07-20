import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Field } from "./Field";

export type ExamLanguage = "en" | "de" | "other";

interface Props {
  title: string;
  language: ExamLanguage;
  onTitleChange: (title: string) => void;
  onLanguageChange: (language: ExamLanguage) => void;
  /** Advance the wizard on Enter within the title field. */
  onSubmit: () => void;
}

/**
 * First step of the from-scratch flow: name the exam and pick its content
 * language (which the solver answers in).
 */
export const MetadataStep = ({
  title,
  language,
  onTitleChange,
  onLanguageChange,
  onSubmit,
}: Props) => {
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
      <Field label="Language">
        <Select
          value={language}
          onValueChange={(v) => onLanguageChange(v as ExamLanguage)}
        >
          <SelectTrigger>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="en">English</SelectItem>
            <SelectItem value="de">German</SelectItem>
            <SelectItem value="other">Other</SelectItem>
          </SelectContent>
        </Select>
      </Field>
      {/* Hidden submit lets Enter advance the step via the shell's primary button. */}
      <button type="submit" className="hidden" aria-hidden tabIndex={-1} />
    </form>
  );
};
