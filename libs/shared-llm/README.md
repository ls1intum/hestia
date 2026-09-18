# shared-llm

Spring Boot library that wires a [Spring AI](https://docs.spring.io/spring-ai/reference/) `ChatClient` against the TUM AET [Logos](https://aet.cit.tum.de/projects/ai/logos/) gateway with sensible defaults, with GWDG SAIA one profile away. Each thesis app in `apps/` adds it as a Gradle dependency, supplies its own API key, and gets an injectable `ChatClient.Builder` plus `EmbeddingModel`.

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
| `spring.ai.openai.embedding.base-url`          | `https://chat-ai.academiccloud.de`       |
| `spring.ai.openai.embedding.options.model`     | `e5-mistral-7b-instruct`                 |

Logos publishes its models under provider-prefixed ids and serves two of them: `openai/gpt-oss-120b`
for text and the multimodal `Qwen/Qwen3.8-27B`. It has **no `/v1/embeddings` endpoint**, so
embeddings go to SAIA. An app that embeds supplies a SAIA key for them:

```yaml
spring:
  ai:
    openai:
      api-key: ${LOGOS_API_KEY:}
      embedding:
        api-key: ${SAIA_API_KEY:}
```

### SAIA instead of Logos

Start the app with the `saia` profile and the defaults change to GWDG SAIA, which serves chat and
embeddings from one endpoint:

| Property                                       | Default under `saia`                     |
|------------------------------------------------|------------------------------------------|
| `spring.ai.openai.base-url`                    | `https://chat-ai.academiccloud.de`       |
| `spring.ai.openai.chat.options.model`          | `openai-gpt-oss-120b`                    |
| `spring.ai.openai.chat.options.vision-model`   | `qwen3.5-27b`                            |

```bash
SPRING_PROFILES_ACTIVE=saia ./gradlew :apps:<app>:server:bootRun
```

Point `spring.ai.openai.api-key` at your SAIA key in the profile's `application-saia.yml`. An app
that names models of its own (a vision model per pipeline phase, say) maps those ids there as well.

The profile is read from `spring.profiles.active` (or `spring.profiles.include`) as the process
starts — an environment variable, a `-D` system property or the command line. A profile activated
from inside a configuration file is too late for this library and selects nothing.

To override, just set the same key in your `application.yml`:

```yaml
spring:
  ai:
    openai:
      chat:
        options:
          model: qwen3.5-122b-a10b
          temperature: 0.2
```

The current SAIA model catalog is available at `https://chat-ai.academiccloud.de/v1/models` (requires the API key) and the human-readable list at <https://docs.hpc.gwdg.de/services/chat-ai/models/index.html>.
