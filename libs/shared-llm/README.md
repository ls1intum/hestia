# shared-llm

Spring Boot library that points [Spring AI](https://docs.spring.io/spring-ai/reference/)'s OpenAI client at the TUM AET [Logos](https://aet.cit.tum.de/projects/ai/logos/) gateway and sets the default models. Each thesis app in `apps/` adds it as a Gradle dependency, supplies its own API key, and gets an injectable `ChatClient.Builder`.

Used by `apps/learninggoalhub` and `apps/workshopper`, so a changed default changes both apps. See [AGENTS.md](AGENTS.md) for the change rules.

```bash
./gradlew :libs:shared-llm:test   # from the repository root; no Docker needed
```

## Usage

### 1. Add the dependency

In your app's `build.gradle.kts`:

```kotlin
dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:1.1.6")
    }
}

dependencies {
    implementation(project(":libs:shared-llm"))
}
```

The Spring AI BOM is required at the consumer because Gradle does not propagate `dependencyManagement` from `:libs:shared-llm`.

Make sure the module is listed in the root `settings.gradle.kts`:

```kotlin
include("libs:shared-llm")
```

### 2. Provide the API key

The library leaves the API key unset on purpose. Set it via `application.yml`:

```yaml
spring:
  ai:
    openai:
      api-key: ${LOGOS_API_KEY:}
```

…and supply `LOGOS_API_KEY` through your `.env`, the JVM process environment, or your deployment platform. Never commit the key.

### 3. Inject and call

```java
@RestController
class MyController {
    private final ChatClient chat;
    MyController(ChatClient.Builder builder) { this.chat = builder.build(); }

    @GetMapping("/hello")
    String hello() {
        return chat.prompt().user("Say hi.").call().content();
    }
}
```

## Defaults

The library registers an `EnvironmentPostProcessor` that fills in low-priority defaults — anything you set in `application.yml`, env vars, or system properties wins.

| Property                                       | Default                                  |
|------------------------------------------------|------------------------------------------|
| `spring.ai.openai.base-url`                    | `https://logos.aet.cit.tum.de`           |
| `spring.ai.openai.chat.options.model`          | `openai/gpt-oss-120b`                    |
| `spring.ai.openai.chat.options.temperature`    | `0.0`                                    |
| `spring.ai.openai.chat.options.vision-model`   | `Qwen/Qwen3.8-27B`                       |

Logos publishes its models under provider-prefixed ids and serves two of them: `openai/gpt-oss-120b`
for text and the multimodal `Qwen/Qwen3.8-27B`. It has **no `/v1/embeddings` endpoint**, so the
library sets no embedding defaults.

To override, just set the same key in your `application.yml`:

```yaml
spring:
  ai:
    openai:
      chat:
        options:
          model: Qwen/Qwen3.8-27B
          temperature: 0.2
```
