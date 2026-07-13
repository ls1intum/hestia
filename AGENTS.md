# AGENTS.md

Repository-wide instructions for AI coding agents working anywhere in the hestia monorepo. App-specific instructions live in `apps/<app>/AGENTS.md` and apply in addition to this file.

## Repository Context

Hestia is a public academic monorepo hosting TUM thesis projects under the ls1intum organization. Each app in `apps/` belongs to a different thesis author; touch only the app you are working on unless explicitly asked otherwise.

## Never Commit

- secrets or credentials — only `.env.example` templates; real values live in gitignored `.env` files
- personal data (usernames, e-mail addresses, private hostnames, local paths like `/Users/<name>/...`)
- copyrighted material (lecture slides/PDFs); test fixtures must be synthetic or freely licensed
- user-specific details in docs — keep repository docs user-agnostic
- never use `git add -A`; stage files by explicit path

## Repo Guard

The repo ships a pre-commit guard (secret scan via gitleaks + binary file guard). One-time setup per clone:

```bash
git config core.hooksPath .githooks
```

The hook requires `gitleaks` (e.g. `brew install gitleaks`). The same checks run in CI on every pull request (`.github/workflows/repo-guard.yml`).

Never bypass the hook (`--no-verify`). For a deliberate, reviewed binary exception, add the exact path to `.githooks/binary-allowlist` in the same commit.

## Commits and Pull Requests

Use Conventional Commits with the app name as scope (e.g. `feat(examlense): ...`, `fix(workshopper): ...`). Use short-lived feature branches and open a pull request against `main`; commit directly to `main` only for small documentation or housekeeping changes. See CONTRIBUTING.md for the full workflow.
