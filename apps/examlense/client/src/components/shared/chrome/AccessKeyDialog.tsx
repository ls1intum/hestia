import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { CopyButton } from "./CopyButton";

/**
 * Shows the account's own access key.
 *
 * Necessary rather than a convenience: accounts are created per browser and there
 * is no password or e-mail recovery, so this key is the only way to reach the same
 * exams from a second browser or after clearing site data. Losing it means losing
 * the account.
 */
export const AccessKeyDialog = ({
  open,
  onOpenChange,
  token,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  token: string;
}) => (
  <Dialog open={open} onOpenChange={onOpenChange}>
    <DialogContent>
      <DialogHeader>
        <DialogTitle className="font-display">Your access key</DialogTitle>
        <DialogDescription>
          This is what identifies your account. Save it somewhere safe — it's the only way back
          into these exams from another browser, or if you clear this browser's data. Anyone who
          has it can act as you, so don't share it.
        </DialogDescription>
      </DialogHeader>
      <div className="flex gap-2">
        <Input readOnly value={token} onFocus={(e) => e.currentTarget.select()} />
        <CopyButton value={token} />
      </div>
    </DialogContent>
  </Dialog>
);
