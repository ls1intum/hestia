# Contributing to Hestia

Welcome to Hestia! This repository is an academic monorepo built for the TUM thesis projects under the ls1intum organization.

## Development Workflow

1. **Branching**: Use feature branches off `main` (e.g., `feat/workshopper-feature`, `fix/learninggoalhub-bug`).
2. **Pull Requests**: Open a PR against `main`. Ensure all CI checks (tests, builds) pass before requesting review.
3. **Commit Messages**: Follow standard conventional commits.
4. **Code Quality**: Keep tests updated. Do not commit secrets, student data (PII), or copyrighted materials.

## One-Time Clone Setup

Enable the repo's pre-commit guard (secret scan + binary file guard) once per clone:

```bash
git config core.hooksPath .githooks
```

The hook requires [gitleaks](https://github.com/gitleaks/gitleaks) (e.g. `brew install gitleaks`). CI runs the same checks on every pull request, so enabling the hook locally saves you a failed PR check. If you deliberately need to add a binary file (e.g. a favicon or a synthetic test fixture), add its exact path to `.githooks/binary-allowlist` in the same commit.

## Local Setup

See the individual `README.md` files in each `apps/` directory for specific instructions on running the backend and frontend components.

Thank you for contributing!
