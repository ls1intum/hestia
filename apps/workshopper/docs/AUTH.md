# Hestia SSO Authentication (SAML 2.0)

This document outlines the authentication architecture for the Hestia suite (Workshopper).

## 1. Overview
Workshopper uses **SAML 2.0** against the Central TUM IT Identity Provider (`login.tum.de`). 
Authentication is strictly enforced on all `GET`, `POST`, `PUT`, and `DELETE` paths under `/api/**` (with explicit carve-outs for `/api/workshop/health`).

## 2. The SAML Flow
1. User navigates to the frontend.
2. Frontend attempts to fetch `GET /api/workshop/sessions` (or `/me`).
3. If the user is unauthenticated, the backend throws an `AccessDeniedException` (caught by the global `@ExceptionHandler`) or Spring Security intercepts it, returning `401 Unauthorized`.
4. The frontend `api.ts` interceptor (`handleAuthError`) catches the 401.
5. The frontend forcefully redirects the browser to `/saml2/authenticate/tum` (the Spring Security SAML entry point).
6. Spring Boot builds the SAML AuthNRequest and redirects to TUM IT.
7. User logs in. TUM IT posts the assertion back to `/login/saml2/sso/tum`.
8. Spring Security validates the assertion, creates the `AuthContext`, and redirects the user back to the application root (`/`).

## 3. Local Development Bypass
To avoid needing TUM VPNs and live SAML certs for local development, we use `DevAuthFilter`.

* **How it works:** When the `local` profile is active, this filter intercepts every request and injects a mock `Saml2AuthenticatedPrincipal` with the ID `dev-local-user`.
* **How to use it:** Simply run the app locally. `application.yml` defaults to the `local` profile.
* **Security:** `SecurityConfig.java` contains a hard profile gate. If it detects Kubernetes or Fly.io environment variables while the `local` profile is active, it throws a `FatalBeanException` and crashes the server.

## 4. Environment Variables
In deployment (`compose.prod.yaml`), the backend expects:
- `SPRING_PROFILES_ACTIVE`: Set to `local` on `hestia-test` to bypass login. Omit or set to `prod` on production to enforce real SAML.
- `SAML_PRIVATE_KEY_LOCATION`: Path to the `.pem` file mounted in Docker.
- `SAML_CERTIFICATE_LOCATION`: Path to the `.crt` file mounted in Docker.
