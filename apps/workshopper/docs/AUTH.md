# Hestia Login and Access (SAML 2.0)

This document outlines how Workshopper authenticates users and decides what they may see.

## 1. Overview
There is **one SAML 2.0 login for the whole Hestia server**, owned by `core`; Workshopper has no login of its own. A user logs in once with the university's identity provider and gets a session cookie that is valid for every module. `core` also keeps the organisation (the university, taken from the login's `schacHomeOrganization` attribute) and the user record.

Everything under `/workshopper/api/**` needs a logged-in user, except `GET /workshopper/api/workshop/health`. The SAML metadata and the identity provider's settings live in configuration files only.

## 2. The Login Flow
1. The user opens the client.
2. The client calls the API, e.g. `GET /api/workshop/sessions` for the dashboard (always with the session cookie).
3. If the user is not logged in, core answers `401 Unauthorized`.
4. The client's `handleAuthError` (`api.ts`) catches the 401 and redirects the browser to `<base>/saml2/authenticate/tum`. On the dashboard it shows a "Log in" screen instead of redirecting straight away.
5. Spring Security builds the SAML AuthNRequest and redirects to the identity provider.
6. The user logs in; the identity provider posts the assertion back to core's login endpoint.
7. Core validates the assertion, creates or updates the user in its organisation and stores the login in the HTTP session. The browser keeps the session cookie.

Modules never read the security context themselves. They ask core's `CurrentUser` bean for the user id and organisation.

## 3. CSRF
The session cookie is sent automatically by the browser, so writes are protected against cross-site requests: every `POST`, `PUT` and `DELETE` must carry the CSRF token. Core sets it as the cookie `XSRF-TOKEN`; the client's request function (`send` in `api.ts`) copies it into the header `X-XSRF-TOKEN` on every non-GET request. A write without a matching header is answered `403`.

## 4. What a User May See: Organisation and Course Roles
* **Organisation:** every table of the teaching module carries the organisation. A user never sees data of another university (the database enforces this with row-level security as well).
* **Courses:** a Workshopper course hangs on a `core` course. The user's role in that course decides: `VIEWER` reads (the course, its sessions, exports), `EDITOR` also changes (drafts, generation, templates, renaming), `OWNER` also deletes the course. The user who creates a course becomes its `OWNER`; members are managed through core's member endpoints.
* **Sessions without a course** belong to their creator alone.
* **Slide templates** are stored with the user who uploaded them.
* A course the user has no role in does not exist for them (`404`); a role that is too small gives `403`, which the client shows as an "Access denied" message.

## 5. Local Development Bypass
To avoid needing live SAML certificates locally, core has a `DevAuthFilter`.

* **How it works:** when the `local` profile is active, every request counts as the user `dev-local-user` of the organisation `local.test`. The headers `X-Dev-User` and `X-Dev-Org` choose another user or organisation (used by tests for "another user" and "another university"). Writes still need the CSRF token.
* **How to use it:** start the server with `SPRING_PROFILES_ACTIVE=local` (see the README). There is no default profile, so the bypass is never on by accident.
* **Security:** core's `CoreSecurityConfig` refuses to start (`IllegalStateException`) if `local` is active together with `prod`.

## 6. Environment Variables
In deployment (`compose.prod.yaml`), the server expects:
- `SPRING_PROFILES_ACTIVE`: defaults to the deploy target (`ENVIRONMENT`, written into `.env` by the deploy), or `prod` without one. `application.yml` maps `Production` to `prod`, which enforces SAML, and `Staging` (`hestia-test`) to `local`, because SAML access was only requested for production; there every tester is the same `dev-local-user`.
- `SAML_PRIVATE_KEY_LOCATION`: path to the `.pem` file mounted into the container (`/opt/hestia/secrets/saml` → `/app/certs`).
- `SAML_CERTIFICATE_LOCATION`: path to the `.crt` file mounted into the container.
