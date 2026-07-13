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
  * Linked the configuration to the local certificates for signing SAML requests.
* **`SecurityConfig.java`:** 
  * Enabled SAML login.
  * Explicitly permitted access to the `/saml2/service-provider-metadata/**` endpoint so Central IT can download the XML without logging in.
  * Left the existing `/api/**` endpoints completely open so local frontend development is not blocked.
* **`certs/private-key.pem` & `certificate.crt`:** 
  * Generated local, self-signed X.509 certificates used to cryptographically sign our login requests.
* **`.gitignore` (Root):** 
  * Explicitly ignored `**/*.pem` and `**/*.crt` files inside `certs/` directories. This ensures that RSA private keys are **never** committed to the public GitHub repository, satisfying our push protection and security rules.

---

## 3. Next Steps & Deployment Guide

To finish the SAML integration and get it fully working, you must complete the following phases:

### Phase A: App Development (Wiring it up)
1. **The Login Button:** Add a "Login with TUM" button to your React frontend that redirects the user to the backend SAML authentication entry point: `/saml2/authenticate/tum`.
2. **The User Profile Endpoint:** Write a quick API endpoint (e.g., `/api/user/me`) in the Spring Boot backend so the React frontend can query the currently logged-in student's name and email after the SAML redirect completes.

### Phase B: VM Deployment Strategy
Because we explicitly ignored the certificates in GitHub, you must handle them manually on the production VM.
1. **Generate Certs on VM:** SSH into the VM, create a secure folder (e.g., `/opt/hestia/secrets/saml/`), and run the `openssl` command to generate the production keys directly on the server.
2. **Mount via Docker Compose:** Update your VM's `docker-compose.yml` to bind-mount that secure folder into all three backend containers (Workshopper, LGHub, ExamLense) at `/app/secrets:ro`.
3. **Update `.env`:** Configure the VM's `.env` file so that Spring Boot knows to read the certs from `/app/secrets` rather than the local classpath.

### Phase C: Pre-Ticket Requirements (For AI Agents & Developers)
Before you can submit the ticket to IT, you must ensure all three apps are generating their SAML metadata and that the Privacy Policy is live.

**1. Replicate SAML Setup for LearningGoalHub & ExamLense:**
An agent or developer must replicate the exact SAML configuration we built for Workshopper in the other two apps:
* Add `spring-boot-starter-security` and `spring-security-saml2-service-provider` to their `build.gradle.kts`.
* Copy the SAML configuration block into their `application.yml`.
* Create the `SecurityConfig.java` to enable `.saml2Login()` and explicitly permit access to `/saml2/service-provider-metadata/**`.
* Once deployed, verify that they each generate their own live metadata URLs (e.g., `https://hestia-test.aet.cit.tum.de/learninggoalhub/saml2/service-provider-metadata/tum`).

**2. Host the Privacy Policy:**
* The Privacy Policy must be a publicly accessible, live webpage before submitting the ticket.
* Create a new React component (e.g., `PrivacyPolicy.tsx`) in your frontend application.
* Add a frontend route so it is accessible at a URL like `https://hestia.aet.cit.tum.de/privacy`.
* The content should be the text adapted from the Artemis privacy policy.

### Phase D: The IT Ticket
Once the backend metadata URLs are live and the Privacy Policy is published, email `it-support@tum.de` with the following:
* **System Owner & Contacts:** The AET Department, and your contact info.
* **App Info:** Name, description, start page URLs, and the live link to your Privacy Policy.
* **Data Requirements:** State that you need TUM ID, Name, Email, and Affiliation.
* **SAML Metadata URLs:** Provide the live metadata URLs generated by Spring Boot for all three apps.
* **Security Confirmation:** Explicitly state that Hestia enforces HTTPS/HSTS, CSRF protection, and blocks iframe embedding.
