import { ApiError } from "@/lib/api/api-client";

export type GateState =
  /** Session resolved — show the app. */
  | "authenticated"
  /** Waiting on /api/me, or on the automatic registration that precedes it. */
  | "resolving"
  /** Fresh browser with no token: create an account without asking anything. */
  | "registering"
  /** Server unreachable or erroring; the user's credential is not in question. */
  | "unreachable"
  /** A token was held and rejected: offer the access-key form, not a silent new account. */
  | "sign-in";

export interface GateInputs {
  /** Whether a usable token exists right now, from any source. */
  hasUsableToken: boolean;
  /** Whether this browser has ever held a token (present key, even if empty). */
  everHadToken: boolean;
  isSuccess: boolean;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  /** Whether automatic registration is in flight or has already failed. */
  registerPending: boolean;
  registerError: unknown;
}

/**
 * Which of the gate's states to show.
 *
 * Extracted from the component because this decides whether anyone reaches the app
 * at all, and the interesting cases are failure modes that are awkward to reach
 * through the UI.
 *
 * Two rules worth stating:
 *
 * 1. Auto-registration requires both that no usable token exists *and* that this
 *    browser has never held one. Registering after a rejection would silently swap
 *    a user onto a new empty account and quietly undo an administrator's revoke;
 *    registering while a token is already usable (the development fallback) would
 *    race the session probe and strand the caller on a stray empty account.
 * 2. The sign-in form is opt-in on a 401, not opt-out on everything else. A 5xx, or
 *    a fetch that never landed (which rejects with a TypeError rather than an
 *    ApiError), says nothing about the user's token — offering a "paste your key"
 *    box there is a dead end, because they have nothing new to paste.
 */
export function gateState(inputs: GateInputs): GateState {
  const { hasUsableToken, everHadToken, isSuccess, isLoading, isError, error, registerPending, registerError } =
    inputs;

  if (isSuccess) return "authenticated";

  // Both conditions are required. "Never stored a token" alone is not enough: a
  // development build compiles one in as a fallback, so nothing is stored while a
  // perfectly usable token exists — registering there would mint a stray account
  // and swap the caller onto it while the session probe was still in flight.
  if (!hasUsableToken && !everHadToken) {
    if (registerError) {
      // A refused registration (closed instance, or the per-IP cap) is not a
      // connectivity problem; the access-key form is the only way forward.
      return registerError instanceof ApiError ? "sign-in" : "unreachable";
    }
    return registerPending ? "resolving" : "registering";
  }

  if (isLoading) return "resolving";

  if (isError) {
    const credentialRejected = error instanceof ApiError && error.status === 401;
    if (!credentialRejected) return "unreachable";
  }

  return "sign-in";
}
