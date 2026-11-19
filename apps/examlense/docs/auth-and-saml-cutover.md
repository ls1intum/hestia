# Auth: the session login of the hestia server

ExamLense used to have its own interim login (per-user bearer tokens, open registration, a shared
bootstrap token). That is gone. The `assessment` module uses the identity and tenancy of the
`core` module like every other module.

## Login

- The browser signs in through TUM SAML (`/saml2/authenticate/tum`) and gets the session cookie
  of the hestia server. In the `local` profile a dev user is signed in instead, never as admin.
  Only with `hestia.dev-login.impersonation=true` (the tests) do the headers `X-Dev-User` /
  `X-Dev-Org` select another one; a deployed server refuses to start with it.
- `client/src/lib/api/api-client.ts` sends the cookie (`credentials: "include"`) and, on every
  non-GET request, the CSRF header `X-XSRF-TOKEN` (value of the `XSRF-TOKEN` cookie). A 401
  sends the browser to the SAML login.
- `GET /api/me` returns the user, the admin flag and the remaining LLM quota.
- Sign-out is `POST api/logout` (204), followed by a page reload.
- SSE streams are authenticated by the same cookie; there is no token in the URL.

## Who sees what

- Every table carries `org_id` (`core.OrgScoped`); Hibernate filters on it and Postgres enforces it
  with the row-level-security policy `org_isolation`.
- An exam with a `course_id` follows `core.CourseAccess`: any course member may read it, editors
  may change it, others get 404. An exam without a course belongs to its creator (`created_by`)
  only.
- Files are stored under `<org_id>/...`; `/api/files/**` needs a login of the same organisation
  and a valid HMAC signature.
- The parse-metrics dashboard (`/api/parse-metrics`) is for platform admins
  (`hestia.security.platform-admins`).

## Migration of old data

`V9__assessment_org_scope.sql` drops `assessment.users` and `assessment.user_tokens`, replaces
`exams.owner_id` by `created_by` and `course_id`, and adds `org_id` plus RLS to all tables. Old
tokens stop working; old accounts are not carried over.
