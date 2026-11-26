# Multi-stage build for the Hestia server.
# Build context is the repository root, where the Gradle wrapper and root build scripts live.

# The build stage only compiles the jar; the JVM that serves requests is the runtime stage below,
# which pins a release fixed against Java4Shell (CVE-2026-114938).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
# Keep the Gradle build memory modest so it fits the small (≈4 GB) deploy VM.
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1024m"

# Gradle wrapper + root build scripts first so dependency layers cache across source-only changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
# Project sources (.dockerignore strips build/, node_modules/, dist/).
COPY server server

RUN ./gradlew --no-daemon :server:bootJar -x test

# Runtime per platform. amd64 (the deploy VMs) pins the fixed release instead of `21-jre`, so a
# rebuild cannot fall back to an open JVM. Temurin has not published 21.0.13.1_2 for arm64 yet, which
# CI also builds; arm64 follows `21-jre` until the pinned tag lists it, then switch it to the pin.
# BuildKit only pulls the stage the target platform uses.
FROM eclipse-temurin:21.0.13.1_2-jre AS runtime-amd64
FROM eclipse-temurin:21-jre AS runtime-arm64

FROM runtime-${TARGETARCH}
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
