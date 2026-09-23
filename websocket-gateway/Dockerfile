# ---- build stage: no local Java/Maven needed on the host ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Resolve dependencies first so code changes don't re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests use Testcontainers (need a Docker daemon) — they run in CI, not in the image build.
RUN mvn -B -q package -DskipTests

# ---- runtime stage: JRE only, non-root ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=build /src/target/websocket-gateway-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
