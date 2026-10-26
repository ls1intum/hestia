# AGENTS.md

Instructions for AI coding agents working on `libs/shared-llm`. The repository-wide rules in the
root `AGENTS.md` apply too.

## Ownership and consumers

This library is maintained by the LearningGoalHub thesis. Two apps depend on it:

| App | Relies on |
| --- | --- |
| `apps/learninggoalhub` | base URL and vision model; sets model and temperature per call |
| `apps/workshopper` | base URL, default model `openai/gpt-oss-120b` and temperature `0.0`; sets only its API key |

A change here ships to both apps on their next deploy. Treat Workshopper as another team's code:
do not edit `apps/workshopper` to adapt it, and announce any change to a default before merging.

## What belongs here

Only what every app needs: the Spring AI OpenAI starter and the Logos defaults in
`HestiaLlmDefaults`. Retries, HTTP timeouts, usage logging and prompts stay in each app, because the
apps tune them differently. LearningGoalHub keeps them in its `llm` package.

## Changing a default

- Every value in `HestiaLlmDefaults.defaults()` is a low-priority fallback. Changing the model or
  temperature changes Workshopper's output, so check its `application.yml` first.
- A new default must be a property an app can still override. Do not register beans; an app that
  wants different behaviour should only need to set a property.
- Keep the table in `README.md` and `HestiaLlmDefaultsTest` in step with the code.

## Verification

```bash
./gradlew :libs:shared-llm:test                       # unit tests, no Docker needed
./gradlew :apps:learninggoalhub:server:test :apps:workshopper:server:test   # the consumers
```

Any change under `libs/**` triggers both apps' pipelines (`learninggoalhub-cicd.yml` and
`workshopper-cicd.yml`), and merging it to `main` redeploys both apps to staging. The LearningGoalHub
pipeline also runs `:libs:shared-llm:test`.

## Commits

Use the `learninggoalhub` scope, since that thesis owns the module, for example
`fix(learninggoalhub): ...` with `shared-llm` named in the subject.
