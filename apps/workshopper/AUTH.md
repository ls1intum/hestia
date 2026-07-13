# Hestia SSO Authentication via Central IT SAML

This document outlines the architecture, configuration, and deployment strategy for integrating the Hestia suite (Workshopper, LearningGoalHub, ExamLense) with the TUM Central IT Identity Provider using the SAML 2.0 protocol.

---

## 1. Overview
We have chosen **Option A: Central TUM IT (SAML)**. 
Unlike modern OIDC where you receive keys first, SAML requires the Service Provider (Hestia) to generate a "Metadata XML" endpoint *first*. Central IT will then consume this endpoint to register the application. 

This means the backend code and certificates must be implemented and deployed before submitting the IT ticket.

---

## 2. What We Have Accomplished
We have successfully prepped the `workshopper-backend` to act as a SAML Service Provider. The following configurations were made:

### Files Created & Modified
* **`build.gradle.kts` (Root & Backend):** 
  * Added the `spring-boot-starter-security` and `spring-security-saml2-service-provider` dependencies. 
  * Added the custom Shibboleth Maven repository to the root project to resolve the required `opensaml` libraries.
* **`application.yml` (Backend):** 
  * Configured Spring Security to use the TUM Identity Provider (`https://login.tum.de/idp/shibboleth`).
  * Updated SAML certificate paths to be configurable via environment variables (`SAML_PRIVATE_KEY_LOCATION`, etc.) with a fallback to the local classpath.
  * Added `server.forward-headers-strategy: framework` to ensure Spring Boot generates `https://` URLs when behind Traefik.
* **`SecurityConfig.java`:** 
  * Enabled SAML login.
  * Explicitly permitted access to the `/saml2/service-provider-metadata/**` endpoint so Central IT can download the XML without logging in.
  * Left the existing `/api/**` endpoints completely open so local frontend development is not blocked.
* **`certs/private-key.pem` & `certificate.crt`:** 
  * Generated local, self-signed X.509 certificates used to cryptographically sign our login requests.
* **`.gitignore` (Root):** 
  * Explicitly ignored `**/*.pem` and `**/*.crt` files inside `certs/` directories. This ensures that RSA private keys are **never** committed to the public GitHub repository, satisfying our push protection and security rules.
* **`compose.prod.yaml` (Deployment):**
  * Mounted the central SAML certificates folder (`/opt/hestia/secrets/saml:/app/certs:ro`) securely as a read-only volume.
  * Injected `SAML_PRIVATE_KEY_LOCATION` and `SAML_CERTIFICATE_LOCATION` so the backend can locate the keys.

---

## 3. Next Steps & Deployment Guide

To finish the SAML integration and get it fully working, you must complete the following phases:

### Phase A: VM Deployment Strategy (COMPLETED)
Because we explicitly ignored the certificates in GitHub, you must handle them manually on the production VM.
1. **Generate Certs on VM (DONE):** We generated the keys directly on the server at `/opt/hestia/secrets/saml/`.
2. **Mount via Docker Compose (DONE):** We updated `compose.prod.yaml` to bind-mount that secure folder into the Workshopper backend container at `/app/certs:ro`.
3. **Update `.env` (DONE):** Configured Spring Boot to read the certs from `/app/certs` using environment variables injected by the compose file.

### Phase B: Pre-Ticket Requirements (For AI Agents & Developers)
Before you can submit the ticket to IT, you must ensure all three apps are generating their SAML metadata and that the Privacy Policy is live.

**1. Replicate SAML Setup for LearningGoalHub & ExamLense:**
An agent or developer must replicate the exact SAML configuration we built for Workshopper in the other two apps:
* Add `spring-boot-starter-security` and `spring-security-saml2-service-provider` to their `build.gradle.kts`.
* Copy the SAML configuration block (including `server.forward-headers-strategy: framework`) into their `application.yml`.
* Create the `SecurityConfig.java` to enable `.saml2Login()` and explicitly permit access to `/saml2/service-provider-metadata/**`.
* Modify their `application.yml` and `compose.prod.yaml` to mount the exact same central certificate folder (`/opt/hestia/secrets/saml:/app/certs:ro`) and read them via environment variables.
* **CRITICAL:** Update their frontend `nginx.conf` file to proxy `location ~ ^/(v3/api-docs|swagger-ui|saml2|login/saml2/sso)` to the backend server. Otherwise, NGINX will block the SAML traffic!
* Once deployed, verify that they each generate their own live metadata URLs (e.g., `https://hestia-test.aet.cit.tum.de/learninggoalhub/saml2/service-provider-metadata/tum`).

**2. Host the Privacy Policy:**
* The Privacy Policy must be a publicly accessible, live webpage before submitting the ticket.
* Create a new React component (e.g., `PrivacyPolicy.tsx`) in your frontend application.
* Add a frontend route so it is accessible at a URL like `https://hestia.aet.cit.tum.de/privacy`.
* The content should be the text adapted from the Artemis privacy policy.

### Phase C: The IT Ticket
Once the backend metadata URLs are live and the Privacy Policy is published, email `it-support@tum.de` with the following:
* **System Owner & Contacts:** The AET Department, and your contact info.
* **App Info:** Name, description, start page URLs, and the live link to your Privacy Policy.
* **Data Requirements:** State that you need TUM ID, Name, Email, and Affiliation.
* **SAML Metadata URLs:** Provide the live metadata URLs generated by Spring Boot for all three apps.
* **Security Confirmation:** Explicitly state that Hestia enforces HTTPS/HSTS, CSRF protection, and blocks iframe embedding.

### Phase D: App Development (Wiring it up, delayed after receiving service)
1. **The Login Button:** Add a "Login with TUM" button to your React frontend that redirects the user to the backend SAML authentication entry point: `/saml2/authenticate/tum`.
2. **The User Profile Endpoint:** Write a quick API endpoint (e.g., `/api/user/me`) in the Spring Boot backend so the React frontend can query the currently logged-in student's name and email after the SAML redirect completes.