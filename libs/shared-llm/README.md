# shared-llm

Spring Boot library that wires a [Spring AI](https://docs.spring.io/spring-ai/reference/) `ChatClient` against the GWDG SAIA endpoint with sensible defaults. Each thesis app in `apps/` adds it as a Gradle dependency, supplies its own API key, and gets an injectable `ChatClient.Builder` plus `EmbeddingModel`.

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
      api-key: ${SAIA_API_KEY:}
```

…and supply `SAIA_API_KEY` through your `.env`, the JVM process environment, or your deployment platform. Never commit the key.

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
| `spring.ai.openai.base-url`                    | `https://chat-ai.academiccloud.de`       |
| `spring.ai.openai.chat.options.model`          | `openai-gpt-oss-120b`                    |
| `spring.ai.openai.chat.options.temperature`    | `0.0`                                    |
| `spring.ai.openai.embedding.options.model`     | `e5-mistral-7b-instruct`                 |
| `spring.ai.openai.chat.options.vision-model`   | `qwen3.5-27b`                            |

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
