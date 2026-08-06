# syntax=docker/dockerfile:1

# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY . .
# BuildKit cache mount keeps the local .m2 repo between builds
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package -DskipTests
# Explode the Spring Boot layered jar so dependencies get their own image layer
RUN mkdir extracted && cd extracted \
 && java -Djarmode=layertools -jar ../pay-bootstrap/target/pay-gateway.jar extract

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre AS runtime
# curl is only needed for the container healthcheck
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --uid 1001 appuser
WORKDIR /app
# Layer order: least volatile first, so a code change only invalidates the last COPY
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
# The reconciliation job archives bill files under ./data
RUN mkdir -p /app/data && chown -R appuser /app/data
USER appuser
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
