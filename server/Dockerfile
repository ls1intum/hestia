# Multi-stage build for the Hestia server.
# Build context is the repository root, because this is a multi-project Gradle build
# (:server depends on :libs:shared-llm).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
# Keep the Gradle build memory modest so it fits the small (≈4 GB) deploy VM.
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1024m"

# Gradle wrapper + root build scripts first so dependency layers cache across source-only changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# Project sources (.dockerignore strips build/, node_modules/, dist/).
COPY libs libs
COPY server server

RUN ./gradlew --no-daemon :server:bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/server/build/libs/hestia-server.jar app.jar
EXPOSE 8080
# Heap is sized from the container memory limit (set in compose) to leave headroom for
# non-heap (PDFBox rendering, Tika) on the shared VM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
# Java4Shell (CVE-2026-114938): the AVX-512 Base64 decoder intrinsic of JDK 17-27 (fixed in 21.0.13.1)
# overflows a heap buffer on crafted input, e.g. a SAMLResponse before login. Switching the intrinsic
# off is the workaround recommended by Adoptium and the BSI; Base64 then uses the plain Java code.
# It comes after $JAVA_OPTS so that an override of JAVA_OPTS cannot turn it back on. Keep it until
# the runtime image is a fixed release (java -version shows 21.0.13.1 or later), then drop it.
# `exec` makes java PID 1, so it receives the stop signal and the smoke test can read its flags.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -XX:-UseBASE64Intrinsics -jar app.jar"]
