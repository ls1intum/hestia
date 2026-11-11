# AGENTS.md

Repository-wide instructions for AI coding agents working anywhere in the hestia monorepo. App-specific instructions live in `apps/<app>/AGENTS.md` and apply in addition to this file.

## Repository Context

Hestia is a public academic monorepo under the ls1intum organization. It started as three separate thesis projects in `apps/` (`examlense`, `learninggoalhub`, `workshopper`) and is being merged into one application with one server and one database. Shared libraries in `libs/` are used by several apps; each has an `AGENTS.md` naming the apps that use it.

## Architecture Is Binding

The merge follows a design document maintained by the project owner outside this repository. Work on the merge starts from a task that provides that document.

- Follow it. Do not invent tables, packages, modules or dependencies it does not name. If a task needs a decision that is missing there or contradicts it, stop and report instead of deciding.
- If you were not given the design document, do not make structural changes (moving code between apps, new shared modules, schema or login changes); ask for it.
- Work on one task per branch. A change may span several apps when the task requires it; keep it to the files the task needs and leave unrelated code alone.
- Do not add new HTTP calls between the servers.
- Move code with `git mv` and a package rename instead of rewriting it.
- Never deploy and never touch production data or secrets. Work on the merge always goes through a pull request.

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

Use Conventional Commits with the app or module name as scope (e.g. `feat(examlense): ...`, `fix(workshopper): ...`, `refactor(core): ...`). Use short-lived feature branches and open a pull request against `main`; commit directly to `main` only for small documentation or housekeeping changes. See CONTRIBUTING.md for the full workflow.
