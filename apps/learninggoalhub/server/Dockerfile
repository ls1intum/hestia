# Multi-stage build for the learninggoalhub Spring Boot server.
# Build context is the repository root, because this is a multi-module Gradle build
# (:apps:learninggoalhub:server depends on :libs:shared-llm).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
# Keep the Gradle build memory modest so it fits the small (≈4 GB) deploy VM.
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1024m"

# Gradle wrapper + root build scripts first so dependency layers cache across source-only changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# Module sources (.dockerignore strips build/, node_modules/, dist/).
COPY libs libs
COPY apps apps

RUN ./gradlew --no-daemon :apps:learninggoalhub:server:bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/apps/learninggoalhub/server/build/libs/learninggoalhub-server.jar app.jar
EXPOSE 8080
# Heap is sized from the container memory limit (set in compose) to leave headroom for
# non-heap (PDFBox rendering, Tika) on the shared VM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
