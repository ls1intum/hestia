import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api/api-client";
import { gateState, type GateInputs } from "./gate-state";

const base: GateInputs = {
  hasUsableToken: true,
  everHadToken: true,
  isSuccess: false,
  isLoading: false,
  isError: false,
  error: null,
  registerPending: false,
  registerError: null,
};

const state = (over: Partial<GateInputs> = {}) => gateState({ ...base, ...over });

describe("gateState", () => {
  it("admits a resolved session", () => {
    expect(state({ isSuccess: true })).toBe("authenticated");
  });

  it("waits while the session is being probed", () => {
    expect(state({ isLoading: true })).toBe("resolving");
  });

  it("registers automatically in a browser with no token that never had one", () => {
    expect(state({ hasUsableToken: false, everHadToken: false })).toBe("registering");
  });

  it("waits while that registration is in flight", () => {
    expect(state({ hasUsableToken: false, everHadToken: false, registerPending: true })).toBe(
      "resolving",
    );
  });

  /**
   * Regression: a development build compiles a fallback token in, so nothing is
   * stored while a usable token exists. Registering there minted a stray account
   * and swapped the caller onto it mid-flight, which showed up as the signed-in
   * user's name beside an empty exam list.
   */
  it("does not register when a token is already usable but nothing was stored", () => {
    expect(state({ hasUsableToken: true, everHadToken: false, isLoading: true })).toBe("resolving");
    expect(state({ hasUsableToken: true, everHadToken: false, isSuccess: true })).toBe(
      "authenticated",
    );
  });

  /**
   * The rule that keeps revocation meaningful: a rejected token must not be
   * silently swapped for a brand-new empty account.
   */
  it("does not auto-register after a token was rejected", () => {
    expect(state({ everHadToken: true, isError: true, error: new ApiError(401, "", "nope") })).toBe(
      "sign-in",
    );
  });

  it("does not auto-register after the user signed out", () => {
    // Sign-out leaves the key present but empty: no usable token, but ever-had is true.
    expect(state({ hasUsableToken: false, everHadToken: true })).toBe("sign-in");
  });

  it("offers the access-key form when registration is refused outright", () => {
    // Closed instance, or the per-IP cap: not a connectivity problem.
    expect(
      state({
        hasUsableToken: false,
        everHadToken: false,
        registerError: new ApiError(403, "", "closed"),
      }),
    ).toBe("sign-in");
    expect(
      state({
        hasUsableToken: false,
        everHadToken: false,
        registerError: new ApiError(429, "", "capped"),
      }),
    ).toBe("sign-in");
  });

  it("reports a failed registration request as unreachable", () => {
    expect(
      state({
        hasUsableToken: false,
        everHadToken: false,
        registerError: new TypeError("Failed to fetch"),
      }),
    ).toBe("unreachable");
  });

  // The failure modes the sign-in form must not swallow.
  it("reports a server error as unreachable rather than blaming the credential", () => {
    expect(state({ isError: true, error: new ApiError(503, "", "Service Unavailable") })).toBe(
      "unreachable",
    );
  });

  it("reports a failed fetch as unreachable", () => {
    // fetch() rejects with a TypeError, which is not an ApiError at all.
    expect(state({ isError: true, error: new TypeError("Failed to fetch") })).toBe("unreachable");
  });

  it("prefers an existing session over everything else", () => {
    expect(state({ hasUsableToken: false, everHadToken: false, isSuccess: true })).toBe(
      "authenticated",
    );
  });
});
