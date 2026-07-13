# Multi-stage build for the ExamLense Spring Boot backend (JDK 21).
# The backend is a standalone Gradle project, so the build context is this directory
# (apps/examlense/backend) — no monorepo root or libs/ dependency.

# ---- build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x ./gradlew && ./gradlew clean bootJar --no-daemon

# ---- run stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
# The executable boot jar (the -plain.jar is not matched by this pinned name).
COPY --from=build /app/build/libs/examlense-backend-0.1.0.jar app.jar
# Serves on server.port=${PORT:8081}; the web (nginx) container proxies to server:8081.
EXPOSE 8081
# Heap is sized from the container mem_limit (set in compose.prod.yaml). JAVA_OPTS is read
# at runtime so compose can override it (sh -c so the env var expands).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
