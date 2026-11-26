# Contributing to Hestia

Welcome to Hestia! This repository is an academic monorepo built for the TUM thesis projects under the ls1intum organization.

## Who Contributes

Hestia is developed by the team of the chair and by students who work on it in a thesis, project or course at TUM. We do not take outside contributions at the moment: please do not ask to be assigned to an issue, and we will not review pull requests from outside the team. You are welcome to report bugs or ask questions in an issue. Security problems go to a private advisory, see `SECURITY.md`.

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

The three apps share one Spring Boot server in `server/` (modules `materials`, `teaching` and `assessment` for LearningGoalHub, Workshopper and ExamLense) and one Postgres; each app keeps its own React client in `apps/<app>/client`.

```bash
docker compose up -d            # the Postgres for local development (compose.yaml)
./gradlew testAll               # all server tests (needs Docker for Testcontainers)
./gradlew :server:bootRun       # the server on http://localhost:8080
```

Before pushing, `scripts/ci-local.sh` runs what the CI checks before it builds images (deploy preflight, server tests, client tests; `--e2e` adds the LearningGoalHub browser tests).

See the `README.md` (or `AGENTS.md`) in each `apps/` directory for how to run and test that app's client and what its module does.

## Planning Work: Issues

These rules apply to everyone, people and AI agents alike.

All planned work lives in the [GitHub Issues](https://github.com/ls1intum/hestia/issues) of this repository: bugs, follow-ups from reviews, features and open questions. There is no backlog or to-do file in the repository.

- **One issue per task.** Short title in the imperative ("Show review marks in LearningGoalHub"). Body: *Problem* (what is wrong or missing, with file paths), *Proposal* (if there is one), *Done when* (how to tell it is finished).
- **Search first** (`gh issue list --search "…" --state all`) and comment on an existing issue instead of opening a duplicate.
- **Labels:** one type (`bug`, `enhancement`, `documentation`, `question`), one or more areas (`app:examlense`, `app:learninggoalhub`, `app:workshopper`, `module:core`, `module:ai`, `infra`), and where it fits `good first issue`, `thesis-candidate` (big enough for a thesis or project) or `decision-needed` (a maintainer has to decide before anyone builds it).
- **Board:** every issue goes onto the project board [Hestia](https://github.com/orgs/ls1intum/projects/142) (`gh project item-add 142 --owner ls1intum --url <issue-url>`). Status: *Ideas* (an idea, or `decision-needed`), *Ready* (clear enough to start), *In progress* (someone works on it; assign yourself), *Done*.
- **Link the pull request** with `Closes #<number>` in its description, so the issue closes on merge. Close an issue you will not do with a one-line reason.
- **Small leftovers** that come up in a review go into one issue per topic, not into code comments.

### What never goes into an issue, pull request, commit or document here

This repository is public. The following stays out of it, also in rewritten or shortened form:

1. **Open security vulnerabilities**: anything an outsider could exploit, such as missing login or role checks, ways around a check, missing rate limits, injection, or data that leaks to the wrong user. Report them as a draft [security advisory](https://github.com/ls1intum/hestia/security/advisories) (see `SECURITY.md`); maintainers add the people who work on the fix, and the advisory is published once the fix is merged. A pull request that fixes one describes the change, not how to exploit it.
2. **Personal data**: names, e-mail addresses or phone numbers of people outside the core team (lecturers, partners, students, study participants), and who said what in a meeting. Write "lecturers asked for X", not who asked.
3. **Partners' material**: lecture slides, scripts, exams, submissions or evaluation results, and quotes from them. Test fixtures are synthetic or freely licensed.
4. **Infrastructure internals**: host names other than the public URLs, IP addresses, VM names, access paths, VPN details, tickets with the IT department.
5. **Secrets** of any kind, also expired or revoked ones.
6. **Research and funding strategy**: unpublished paper plans, grant applications, collaboration talks, and priorities tied to people.
7. **Local details**: local paths, user names, machine names.

If you are not sure whether something may be public, do not post it and ask a maintainer first.

Thank you for contributing!
