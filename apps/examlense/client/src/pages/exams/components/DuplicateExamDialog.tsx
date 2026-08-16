import { useState } from "react";
import { Loader2 } from "lucide-react";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import type { ExamListItem } from "@/lib/api/api-client";
import { useSolverModels } from "@/lib/api/api-models";
import { resolveSelectableDefault, selectableModels } from "@/lib/exam/llm-models";
import { Field } from "@/pages/exams/start-exam/Field";
import { SolverModelStep } from "@/pages/exams/start-exam/SolverModelStep";

interface Props {
  /** `null` keeps the dialog closed. Mount with `key={exam?.id}` so the form
   *  re-seeds from a fresh row each time it opens. */
  exam: ExamListItem | null;
  onOpenChange: (open: boolean) => void;
  onConfirm: (payload: { title: string; solver_model: string }) => Promise<void>;
}

/**
 * Duplicate an exam with an editable title + solver model. The copy defaults to
 * "<title> (Copy)" and the source's solver; the picker falls back to a valid
 * default if the source model is no longer selectable.
 */
export const DuplicateExamDialog = ({ exam, onOpenChange, onConfirm }: Props) => {
  const { data: solverCatalog } = useSolverModels();
  const [title, setTitle] = useState(`${exam?.title ?? ""} (Copy)`);
  const [solverId, setSolverId] = useState(() =>
    resolveSelectableDefault(
      selectableModels(solverCatalog?.models ?? []),
      exam?.solver_model ?? undefined,
    ),
  );
  const [busy, setBusy] = useState(false);

  const canConfirm = !!title.trim() && !!solverId && !busy;

  const confirm = async () => {
    if (!canConfirm) return;
    setBusy(true);
    try {
      await onConfirm({ title: title.trim(), solver_model: solverId });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={!!exam} onOpenChange={(open) => !busy && onOpenChange(open)}>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Duplicate exam</DialogTitle>
          <DialogDescription>
            Copies all sections, tasks, and figures into a new draft. Adjust the title and the model
            that will solve the copy.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-hestia-4">
          <Field label="Title">
            <Input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              autoFocus
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  confirm();
                }
              }}
            />
          </Field>
          <div>
            <span className="mb-1 block text-xs font-medium text-hestia-text-muted">
              Solver model
            </span>
            <SolverModelStep value={solverId} onChange={setSolverId} />
          </div>
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="ghost" disabled={busy}>
              Cancel
            </Button>
          </DialogClose>
          <Button onClick={confirm} disabled={!canConfirm}>
            {busy ? <Loader2 size={14} className="mr-2 animate-spin" /> : null}
            Duplicate
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};
