/**
 * Where the session token lives on the client.
 *
 * The token cannot come from Vite env like the old shared one did: `VITE_*` is
 * inlined into the bundle at build time, so a per-user credential would be baked
 * into JavaScript served to everyone. It has to be a runtime value, so it lives
 * in `localStorage` and every read goes through here.
 */
const STORAGE_KEY = "examlense.auth.token";

/**
 * Dev convenience only — the shared bootstrap token, which authenticates as the
 * seeded legacy user. Lets `npm run dev` skip the sign-in gate entirely.
 *
 * Gated on `import.meta.env.DEV` deliberately. `client/.env.production` also
 * bakes a value in, and honouring it in a production build would mean sign-out
 * silently signed you back in as the shared legacy user — and that every visitor
 * started out authenticated as it. Production users enrol instead.
 */
const DEV_FALLBACK = import.meta.env.DEV
  ? ((import.meta.env.VITE_API_AUTH_TOKEN ?? "") as string)
  : "";

/** Notified when the token changes so the gate can re-render without a reload. */
type Listener = () => void;
const listeners = new Set<Listener>();

/**
 * A stored key always wins, even when empty — that is what makes sign-out stick.
 * `DEV_FALLBACK` applies only when nothing was ever stored, so it cannot
 * resurrect a session the user deliberately ended.
 */
export function getToken(): string {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored !== null ? stored : DEV_FALLBACK;
  } catch {
    // Private-mode Safari and similar throw on localStorage access.
    return DEV_FALLBACK;
  }
}

export function setToken(token: string): void {
  try {
    localStorage.setItem(STORAGE_KEY, token);
  } catch {
    /* ignore quota/permission errors — the in-memory session still works */
  }
  listeners.forEach((l) => l());
}

/** Writes an empty value rather than removing the key — see {@link getToken}. */
export function clearToken(): void {
  try {
    localStorage.setItem(STORAGE_KEY, "");
  } catch {
    /* ignore */
  }
  listeners.forEach((l) => l());
}

export function hasToken(): boolean {
  return getToken().length > 0;
}

export function subscribeToken(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Whether this browser has ever held a token.
 *
 * The gate uses this to decide between registering automatically and showing the
 * sign-in form. A key that is present but empty means the user signed out or their
 * token was rejected — auto-registering there would silently hand them a new empty
 * account, and would undo an administrator's revoke.
 */
export function hasEverStoredToken(): boolean {
  try {
    return localStorage.getItem(STORAGE_KEY) !== null;
  } catch {
    return false;
  }
}
