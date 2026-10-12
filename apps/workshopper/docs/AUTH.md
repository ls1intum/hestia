# Hestia SSO Authentication (SAML 2.0)

This document outlines the authentication architecture of Workshopper.

## 1. Overview
Workshopper uses **SAML 2.0** against the Central TUM IT Identity Provider (`login.tum.de`).
Authentication is enforced on every path under `/api/**`, except `GET /api/workshop/health` and the SAML service-provider metadata (`/saml2/service-provider-metadata/**`).

## 2. The SAML Flow
1. The user opens the client.
2. The client calls the API, e.g. `GET /api/workshop/sessions` for the dashboard.
3. If the user is not logged in, Spring Security answers `401 Unauthorized` (`HttpStatusEntryPoint` for `/api/**`).
4. The client's `handleAuthError` (`api.ts`) catches the 401 and redirects the browser to `<base>/saml2/authenticate/tum` (e.g. `/workshopper/saml2/authenticate/tum` behind the reverse proxy). On the dashboard it shows a "Log in with TUM" screen instead of redirecting straight away.
5. Spring Boot builds the SAML AuthNRequest and redirects to TUM IT.
6. The user logs in. TUM IT posts the assertion back to `/login/saml2/sso/tum`.
7. Spring Security validates the assertion, stores the `Saml2AuthenticatedPrincipal` in the security context and redirects the user back to the application.

On the server, `AuthContext.getCurrentUserId()` reads the logged-in user's id from the security context; it is used as the owner of sessions, courses and templates.

A logged-in user who accesses another user's session or course gets `403 Forbidden` (`AccessDeniedException`), which the client shows as an "Access denied" message.

## 3. Local Development Bypass
To avoid needing the TUM VPN and live SAML certificates locally, there is `DevAuthFilter`.

* **How it works:** when the `local` profile is active, the filter signs every request in as `dev-local-user` (a `UsernamePasswordAuthenticationToken` with `ROLE_USER`).
* **How to use it:** start the server with `SPRING_PROFILES_ACTIVE=local` (see the README). There is no default profile, so the bypass is never on by accident.
* **Security:** `SecurityConfig.java` refuses to start (`IllegalStateException`) if `local` is active together with `prod`, or inside Kubernetes or Fly.io.

## 4. Environment Variables
In deployment (`compose.prod.yaml`), the server expects:
- `SPRING_PROFILES_ACTIVE`: defaults to `prod`, which enforces SAML. `hestia-test` sets `local` in its `.env`, because SAML access was only requested for production; there every tester is the same `dev-local-user`.
- `SAML_PRIVATE_KEY_LOCATION`: path to the `.pem` file mounted into the container (`/opt/hestia/secrets/saml` → `/app/certs`).
- `SAML_CERTIFICATE_LOCATION`: path to the `.crt` file mounted into the container.
