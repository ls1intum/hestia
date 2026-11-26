# Multi-stage build for the Hestia server.
# Build context is the repository root, where the Gradle wrapper and root build scripts live.

# Both stages pin a fixed Temurin release instead of `21-jdk`/`21-jre`, so a rebuild cannot fall
# back to a JDK open to Java4Shell (CVE-2026-114938, fixed in 21.0.13.1). Raise both together.
FROM eclipse-temurin:21.0.13.1_2-jdk AS build
WORKDIR /workspace
# Keep the Gradle build memory modest so it fits the small (≈4 GB) deploy VM.
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1024m"

# Gradle wrapper + root build scripts first so dependency layers cache across source-only changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# Project sources (.dockerignore strips build/, node_modules/, dist/).
COPY server server

RUN ./gradlew --no-daemon :server:bootJar -x test

FROM eclipse-temurin:21.0.13.1_2-jre
WORKDIR /app
COPY --from=build /workspace/server/build/libs/hestia-server.jar app.jar
EXPOSE 8080
# Heap is sized from the container memory limit (set in compose) to leave headroom for
# non-heap (PDFBox rendering, Tika) on the shared VM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
# Java4Shell (CVE-2026-114938): the AVX-512 Base64 decoder intrinsic of JDK 17-27 (fixed in 21.0.13.1)
# overflows a heap buffer on crafted input, e.g. a SAMLResponse before login. The image above is a
# fixed release; switching the intrinsic off as well (the workaround of Adoptium and the BSI) is a
# second line in case the base image is ever lowered. It comes after $JAVA_OPTS so that an override of
# JAVA_OPTS cannot turn it back on. On a deploy target the server itself refuses to start with an
# open JVM and the intrinsic on (Java4ShellGuard).
# `exec` makes java PID 1, so it receives the stop signal and the smoke test can read its flags.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -XX:-UseBASE64Intrinsics -jar app.jar"]
