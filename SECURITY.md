# Security Policy

## Supported Versions
Currently, this repository is a research prototype. Security updates are applied to the `main` branch on a best-effort basis.

## Reporting a Vulnerability
If you discover a security vulnerability within Hestia, please DO NOT open a public issue. 
Instead, please email the project maintainers or the TUM thesis advisors directly. 
Include detailed steps to reproduce the vulnerability. We will endeavor to respond within 5 business days and coordinate a fix.

## Authentication
Apps that are deployed publicly must authenticate their users. Workshopper, for example, uses TUM single sign-on (SAML 2.0) in production.

Some apps have a development bypass that signs every request in as one fixed user (Workshopper's `local` Spring profile). It is meant for local development and VPN-internal test machines only. Never enable such a bypass on a publicly reachable deployment; Workshopper refuses to start if it is combined with the `prod` profile. See each app's documentation (e.g. `apps/workshopper/docs/AUTH.md`) for details.
