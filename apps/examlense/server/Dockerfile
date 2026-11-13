# Multi-stage build for the ExamLense Spring Boot server (JDK 21).
# Build context is the repository root, because the server is a project of the root
# multi-module Gradle build (:apps:examlense:server).

# ---- build stage ----
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

RUN ./gradlew --no-daemon :apps:examlense:server:bootJar -x test

# ---- run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/apps/examlense/server/build/libs/examlense-server.jar app.jar
# Serves on server.port=${PORT:8081}; the web (nginx) container proxies to server:8081.
EXPOSE 8081
# Heap is sized from the container mem_limit (set in compose.prod.yaml). JAVA_OPTS is read
# at runtime so compose can override it (sh -c so the env var expands).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
