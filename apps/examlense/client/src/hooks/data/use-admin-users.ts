import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  listAdminUsers,
  revokeUserTokens,
  setUserAdmin,
  type AdminUser,
} from "@/lib/api/api-client";
import { meKey } from "@/hooks/data/use-me";

export const adminUsersKey = ["admin", "users"] as const;

export function useAdminUsers() {
  return useQuery({
    queryKey: adminUsersKey,
    queryFn: async () => (await listAdminUsers()) as AdminUser[],
  });
}

export function useSetUserAdmin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, isAdmin }: { id: string; isAdmin: boolean }) => setUserAdmin(id, isAdmin),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: adminUsersKey });
      // Own admin flag drives the /admin route guard and the header menu.
      qc.invalidateQueries({ queryKey: meKey });
    },
  });
}

export function useRevokeUserTokens() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => revokeUserTokens(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: adminUsersKey }),
  });
}
