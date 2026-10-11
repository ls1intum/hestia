import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { toast } from "sonner";
import { ApiError } from "@/lib/api/api-client";
import { useMe } from "@/hooks/data/use-me";
import {
  useAdminUsers,
  useRevokeUserTokens,
  useSetUserAdmin,
} from "@/hooks/data/use-admin-users";
import { PanelMessage } from "./PanelMessage";
import { MetricsTable, type MetricsColumn } from "./MetricsTable";

const errorText = (err: unknown, fallback: string) =>
  err instanceof ApiError ? (err.message ?? fallback) : fallback;

/**
 * The registered-user roster, with admin promotion and revocation.
 *
 * Registration is open, so this is mostly a window on who is actually using the
 * instance — and specifically on who has *not* linked a TUM ID, since those
 * accounts have nothing for a TUM login to match and will not carry their exams
 * across the switch to TUM sign-in.
 */
export const UserAccessPanel = () => {
  const { data: me } = useMe();
  const { data: users, isLoading, error } = useAdminUsers();
  const setAdmin = useSetUserAdmin();
  const revoke = useRevokeUserTokens();

  const columns: MetricsColumn<NonNullable<typeof users>[number]>[] = [
    {
      header: "User",
      cell: (u) => (
        <span className="flex items-center gap-2">
          {u.external_id.startsWith("anon-") ? (
            <span className="text-hestia-text-muted" title="No TUM ID linked yet">
              {u.external_id}
            </span>
          ) : (
            u.external_id
          )}
          {u.is_admin && <Badge variant="secondary">admin</Badge>}
          {u.id === me?.id && <span className="text-xs text-hestia-text-muted">(you)</span>}
        </span>
      ),
    },
    {
      header: "Access",
      cell: (u) =>
        u.has_active_token ? (
          "Active"
        ) : (
          <span className="text-hestia-text-muted">None</span>
        ),
    },
    {
      header: "Actions",
      align: "right",
      cell: (u) => {
        // Guarded server-side too: demoting yourself could leave the deployment
        // with no administrator and no way back in without database access.
        const isSelf = u.id === me?.id;
        return (
          <span className="flex justify-end gap-2">
            <Button
              variant="ghost"
              size="sm"
              disabled={isSelf || setAdmin.isPending}
              onClick={() =>
                setAdmin.mutate(
                  { id: u.id, isAdmin: !u.is_admin },
                  { onError: (e) => toast.error(errorText(e, "Could not change admin.")) },
                )
              }
            >
              {u.is_admin ? "Remove admin" : "Make admin"}
            </Button>
            <Button
              variant="ghost"
              size="sm"
              disabled={isSelf || !u.has_active_token || revoke.isPending}
              onClick={() =>
                revoke.mutate(u.id, {
                  onSuccess: () => toast.success(`Revoked access for ${u.external_id}.`),
                  onError: (e) => toast.error(errorText(e, "Could not revoke access.")),
                })
              }
            >
              Revoke
            </Button>
          </span>
        );
      },
    },
  ];

  return (
    <div className="space-y-hestia-5">
      {isLoading ? (
        <PanelMessage>Loading users…</PanelMessage>
      ) : error ? (
        <PanelMessage>{errorText(error, "Could not load users.")}</PanelMessage>
      ) : (
        <MetricsTable
          title="Enrolled users"
          columns={columns}
          rows={users ?? []}
          getRowKey={(u) => u.id}
        />
      )}
    </div>
  );
};
