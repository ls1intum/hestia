import { describe, expect, it, beforeEach, vi, afterEach } from "vitest";

/**
 * The store's two non-obvious rules, both load-bearing:
 *  - a stored empty value beats the dev fallback, so sign-out actually sticks
 *    (otherwise the token baked in at build time would sign the user straight
 *    back in as the shared legacy user);
 *  - "never stored" and "stored but empty" are different states, because the
 *    sign-in gate auto-registers only in the first case.
 */
describe("token-store", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  const load = () => import("./token-store");

  it("falls back to the dev bootstrap token when nothing was ever stored", async () => {
    const { getToken, hasToken } = await load();
    // Vitest runs with import.meta.env.DEV true, matching `npm run dev`: the
    // fallback is what lets local development skip the sign-in gate. A production
    // build compiles it out, so deployed users always enrol.
    expect(getToken()).toBe("dev-local-token");
    expect(hasToken()).toBe(true);
  });

  it("round-trips a stored token", async () => {
    const { setToken, getToken, hasToken } = await load();
    setToken("exl_abc");
    expect(getToken()).toBe("exl_abc");
    expect(hasToken()).toBe(true);
  });

  it("clearing beats the dev fallback, so sign-out is not silently undone", async () => {
    const { setToken, clearToken, getToken, hasToken } = await load();
    setToken("exl_abc");
    clearToken();
    expect(getToken()).toBe("");
    expect(hasToken()).toBe(false);
  });

  it("clearing writes an empty value rather than removing the key, so no fallback can revive it", async () => {
    const { setToken, clearToken } = await load();
    setToken("exl_abc");
    clearToken();
    expect(localStorage.getItem("examlense.auth.token")).toBe("");
  });

  it("notifies subscribers on sign-in and sign-out", async () => {
    const { setToken, clearToken, subscribeToken } = await load();
    const seen: boolean[] = [];
    const unsubscribe = subscribeToken(() => seen.push(true));

    setToken("exl_abc");
    clearToken();
    unsubscribe();
    setToken("exl_def"); // after unsubscribe — must not be observed

    expect(seen).toHaveLength(2);
  });

  describe("hasEverStoredToken", () => {
    it("is false in a browser that has never signed in", async () => {
      const { hasEverStoredToken } = await load();
      expect(hasEverStoredToken()).toBe(false);
    });

    it("is true after signing in", async () => {
      const { setToken, hasEverStoredToken } = await load();
      setToken("exl_abc");
      expect(hasEverStoredToken()).toBe(true);
    });

    /**
     * The distinction the sign-in gate relies on: after sign-out there is no usable
     * token, but the browser has held one — so it must show the access-key form
     * rather than silently registering a new empty account.
     */
    it("stays true after signing out, even though no token is usable", async () => {
      const { setToken, clearToken, hasEverStoredToken, hasToken } = await load();
      setToken("exl_abc");
      clearToken();

      expect(hasToken()).toBe(false);
      expect(hasEverStoredToken()).toBe(true);
    });
  });
});
