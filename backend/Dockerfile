# backend/Dockerfile - builds the API/worker image. Build from the REPOSITORY ROOT:
#   docker build -f backend/Dockerfile -t pifa-api .
# Two stages: a big image with Maven+JDK compiles the code, then only the jar is copied into a
# small JRE image, so the final image has no build tools or source code.
# The same image runs as "api" (PIFA_JOBS_ENABLED=false) or "worker" (PIFA_JOBS_ENABLED=true).

# ---- Stage 1: build -------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Copy only what the build needs. The parent pom refers to ../rules, so both folders are required.
COPY backend/pom.xml backend/pom.xml
COPY backend/app backend/app
COPY rules rules
# -q = quiet; tests are skipped here because CI runs them in a separate job.
RUN mvn -q -f backend/pom.xml -DskipTests package

# ---- Stage 2: runtime -----------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/backend/app/target/pifa-app-*.jar app.jar
# 8080 = HTTP API (behind the ingress); 9091 = management: health probes and metrics (internal only).
EXPOSE 8080 9091
# Size the heap from the container memory limit instead of the host's RAM, and exit (so Kubernetes restarts
# the pod) rather than limp on after an OutOfMemoryError.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Run as a non-root user (uid 1000) to limit damage if the app is compromised.
USER 1000
ENTRYPOINT ["java", "-jar", "app.jar"]
