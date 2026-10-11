import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getMe } from "@/lib/api/api-client";
import { hasToken, subscribeToken } from "@/lib/api/token-store";
import { useEffect, useState } from "react";
import type { Me } from "@/lib/api/api-client";

export const meKey = ["me"] as const;

/**
 * The signed-in user. Also the token-validity probe the sign-in gate waits on:
 * a stored token that the server rejects fails this query, and `apiRequest`
 * clears the token on the 401, which flips `useHasToken` back to false.
 */
export function useMe() {
  const tokenPresent = useHasToken();
  return useQuery({
    queryKey: meKey,
    enabled: tokenPresent,
    queryFn: async () => (await getMe()) as Me,
    // A rejected token is not a transient failure; retrying just delays the gate.
    retry: false,
    staleTime: 60_000,
  });
}

/** Re-renders on sign-in and sign-out, so neither needs a page reload. */
export function useHasToken(): boolean {
  const [present, setPresent] = useState(hasToken);
  useEffect(() => subscribeToken(() => setPresent(hasToken())), []);
  return present;
}

/** Drop cached server state on sign-out so the next user never sees it. */
export function useResetOnSignOut() {
  const qc = useQueryClient();
  return () => qc.clear();
}
