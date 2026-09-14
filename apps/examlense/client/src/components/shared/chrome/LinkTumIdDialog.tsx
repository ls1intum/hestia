import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { toast } from "sonner";
import { ApiError, linkTumId } from "@/lib/api/api-client";
import { useQueryClient } from "@tanstack/react-query";
import { meKey } from "@/hooks/data/use-me";

/**
 * Attaches a TUM ID to an anonymous account.
 *
 * Worth prompting for even though nothing requires it: accounts are created
 * anonymously, and TUM sign-in will match people by TUM ID. An account without one
 * has nothing to match, so its exams will not follow the user across that switch.
 */
export const LinkTumIdDialog = ({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) => {
  const qc = useQueryClient();
  const [tumId, setTumId] = useState("");
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    try {
      await linkTumId(tumId);
      await qc.invalidateQueries({ queryKey: meKey });
      toast.success("TUM ID saved.");
      onOpenChange(false);
      setTumId("");
    } catch (err) {
      toast.error(
        err instanceof ApiError
          ? (err.message ?? "Couldn't save that TUM ID.")
          : "Couldn't save that TUM ID.",
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle className="font-display">Add your TUM ID</DialogTitle>
          <DialogDescription>
            ExamLense will move to TUM sign-in. Adding your TUM ID now links this account to your
            TUM login, so your exams stay yours after that switch. Without it, this account can't
            be matched to you.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="space-y-hestia-4">
          <div className="space-y-hestia-2">
            <Label htmlFor="tum-id">TUM ID</Label>
            <Input
              id="tum-id"
              value={tumId}
              onChange={(e) => setTumId(e.target.value)}
              placeholder="ab12cde"
              autoFocus
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              required
            />
            <p className="text-xs text-hestia-text-muted">
              The username you use for TUMonline — not your e-mail address.
            </p>
          </div>
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Not now
            </Button>
            <Button type="submit" disabled={busy}>
              {busy ? "Saving…" : "Save"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
};
