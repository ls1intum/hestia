// Single place to wire the external integrations. Every value can be overridden at build
// time via VITE_ env vars; the defaults below are the real production values, so the page
// works out of the box. (Historically these were placeholders marked with "Platzhalter"
// badges — the badges are removed at a call site once its integration is wired.)

/** Listmonk subscribe endpoint. The public subscription form POSTs here. */
export const NEWSLETTER_ENDPOINT =
  import.meta.env.VITE_NEWSLETTER_ENDPOINT ?? "https://listmonk.aet.cit.tum.de/subscription/form";

/** UUID of the Listmonk list to subscribe to (HESTIA — research & general info). */
export const NEWSLETTER_LIST_UUID =
  import.meta.env.VITE_NEWSLETTER_LIST_UUID ?? "e86b1dc4-a0f5-437b-96e8-53a1c7dac29b";

/** Nextcloud upload link for donated teaching material. */
export const NEXTCLOUD_UPLOAD_URL =
  import.meta.env.VITE_NEXTCLOUD_UPLOAD_URL ??
  "https://nextcloud.cit.tum.de/index.php/apps/forms/s/83cHm9xDGRX7ZMnm8T7Eokyz";

/** Contact address for Klausur-Check requests. */
export const CONTACT_EMAIL = import.meta.env.VITE_CONTACT_EMAIL ?? "ben.lenk-ostendorf@tum.de";

export const KLAUSUR_CHECK_MAILTO = `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent("Klausur-Check anfragen")}`;

/**
 * Deployment environment. The test-system banner shows for every value except
 * "production" — set VITE_ENVIRONMENT=production on the Production build to hide it.
 */
export const ENVIRONMENT = import.meta.env.VITE_ENVIRONMENT ?? "test";
export const IS_PRODUCTION = ENVIRONMENT === "production";
