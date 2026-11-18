import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getMe } from "@/lib/api/api-client";
import type { Me } from "@/lib/api/api-client";

export const meKey = ["me"] as const;

/**
 * The signed-in user. Also the session probe the sign-in gate waits on: a 401 makes
 * `apiRequest` send the browser to the SAML login.
 */
export function useMe() {
  return useQuery({
    queryKey: meKey,
    queryFn: async () => (await getMe()) as Me,
    // A missing session is not a transient failure; retrying just delays the login.
    retry: false,
    staleTime: 60_000,
  });
}

/** Drop cached server state on sign-out so the next user never sees it. */
export function useResetOnSignOut() {
  const qc = useQueryClient();
  return () => qc.clear();
}
