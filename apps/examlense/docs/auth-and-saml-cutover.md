# Auth: interim tokens today, TUM SAML later

## Why this shape

Exams were always meant to be private to their creator, and almost all of the machinery for
that already existed: `exams.owner_id` is `not null` and indexed, the list endpoint queries
`findByOwnerIdOrderByCreatedAtDesc`, `Access.requireExam` returns 404/403, and
`@CurrentUser String userId` is threaded through every controller. The one thing missing was
real identities — the auth filter mapped every valid token to a single constant user, which is
why everyone saw everything.

So this phase adds identity and nothing else. **Resolving the principal to a real user id is
what switched the existing ownership checks on.**

TUM SAML is the destination, but it depends on TUM Central IT registering us as a service
provider. Rather than wait, interim auth was built so that the cutover is a swap of *how a
token is obtained* — not a data migration.

## How the interim flow works

```
first visit, no token  ──►  POST /api/auth/register   (unauthenticated)
                                  │  creates a user with an `anon-…` handle
                                  ▼
                          session token in `user_tokens`  ──►  Bearer on every request
                                  │
                          (optional, any time later)
                                  ▼
                          PATCH /api/me {external_id}  ──►  handle replaced by their TUM ID
```

Registration is **open**: nobody needs an invite, and nothing is asked of the user.
That is a deliberate trade for a VPN-internal prototype — the alternative, invite-only,
meant the thesis author had to hand out a link before anyone could try the app at all.

Design points worth keeping:

- **Accounts start anonymous.** `external_id` is a server-generated `anon-<hex>` handle.
  Because the server generates it, nobody can register *as* someone else — which open
  registration would otherwise allow if the user typed their own identifier.
- **The TUM ID is optional and linked later.** It is what a TUM login matches on, so
  linking is what carries an account's exams across the cutover. An unlinked account has
  nothing to match and will not survive it — hence the nudge in the account menu and the
  `has_tum_id` flag on `GET /api/me`. Linking refuses an identifier another account
  already holds, or the cutover would hand a stranger's exams to whoever the IdP resolves
  to first.
- **Registration is capped per IP per day** (`app.auth.registrations-per-ip-per-day`,
  default 5). Load-bearing, not hygiene: per-user LLM quotas bound nothing if clearing
  browser storage mints a fresh account with a fresh allowance. The stored value is a
  SHA-256 of the address, not the address, so no personal data is retained.
- **An instance-wide LLM ceiling is the actual bound on spend**
  (`app.quota.global-*-per-day`). Both per-identity controls above are defeatable — accounts
  can be churned, and the IP behind them is only as trustworthy as the proxy chain
  (`app.security.ClientIp`). The global count is derived from recorded usage, so it holds
  regardless. Per-user quotas remain, but as a fairness control rather than a spend cap.
- **`app.auth.open-registration=false`** freezes the instance to existing token holders
  without breaking them — the escape hatch if the app ever gets wider exposure than the VPN.
- **`findOrCreateByExternalId` is the only creation-by-identifier path.** Registration uses
  a generated handle; the SAML success handler will call this, so both agree on
  normalization.
- **The access key is the only recovery route.** There is no password and no e-mail, so an
  account lives and dies with its token. `Account → Access key` exists so a user can copy
  it to a second browser; losing it means losing the account.
- **Two filter chains.** `saml2Login` needs a session to hold the in-flight authn request,
  and the API chain is deliberately `STATELESS`. Before the split there was a single
  stateless chain calling `saml2Login()`, which meant a SAML handshake could never have
  completed — only the SP metadata endpoint worked, which is the part Central IT needs.
- **Admin is a separate credential** from the shared dev token, because that one's value is
  committed to a public repository. See `DEPLOY.md` → **Security model**.

## Doing the cutover

Everything below is additive; the API chain does not change, because it still just resolves a
bearer token to a user id.

1. Get the SP registered. The metadata Central IT needs is already served at
   `/saml2/service-provider-metadata/tum`; the relying-party config is in `application.yml`
   under `spring.security.saml2`. Real signing keys go in
   `server/src/main/resources/certs/` (gitignored) or via `SAML_PRIVATE_KEY_LOCATION` /
   `SAML_CERTIFICATE_LOCATION`.
2. Decide what happens to accounts with no TUM ID linked. They cannot be matched, so
   either accept that they are abandoned (fine if the data is throwaway by then) or add a
   one-time "link this browser's account" step for users who still hold their access key.
3. Add a `Saml2AuthenticationSuccessHandler` on the SAML chain that:
   - reads `eduPersonPrincipalName` from the assertion,
   - passes it through `UserService.normalizeExternalId` + `findOrCreateByExternalId`,
   - calls `UserService.mintToken(...)`, and
   - redirects to the SPA with the token (fragment, not query — see the SSE caveat in
     `DEPLOY.md`).

   Confirm what the IdP actually releases before trusting it. If it asserts a bare `ab12cde`
   rather than an eppn, normalization already handles both.
4. Swap the client's `SignInGate` for a "Sign in with TUM" redirect. `token-store.ts` and
   `apiToken()` stay exactly as they are.
5. Retire the interim path: set `app.auth.open-registration=false`, revoke outstanding
   tokens (`DELETE /api/admin/users/{id}/tokens`), clear `API_AUTH_TOKEN` and
   `ADMIN_BOOTSTRAP_TOKEN`, and delete the register endpoint and the access-key UI.

`users` and `user_tokens` survive. **No exam data moves for anyone who linked a TUM ID** — a
TUM login lands on the row they have been using all along.

## Known limitation

The session token travels in the SSE URL query string (`client/src/lib/api/sse.ts`), because
`EventSource` cannot set headers. With per-user tokens a leaked nginx access log is an account
takeover rather than merely shared access. If that matters before SAML lands, the cheap fix is
a `POST /api/sse-ticket` endpoint returning a single-use ~30s token accepted only on the
`/events` routes.

The other one, accepted knowingly: a token sitting in `localStorage` can be read by anything
that achieves script execution on the origin, and there is no second factor, so a stolen
token is a full account takeover with no way to detect it. Revocation is the only remedy
(`DELETE /api/admin/users/{id}/tokens`).
