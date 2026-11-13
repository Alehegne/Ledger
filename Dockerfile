# Stage 1: Build & Layer Extraction
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace

# Cache maven dependencies layer
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw && ./mvnw -B dependency:go-offline || true

# Build application uber-jar
COPY src src
RUN ./mvnw -B clean package -DskipTests

# Extract Spring Boot layers with launcher for optimized Docker caching
RUN java -Djarmode=tools -jar target/Ledger_Final.jar extract --launcher \
    --layers dependencies,spring-boot-loader,snapshot-dependencies,application \
    --destination /workspace/extracted

# Stage 2: Hardened Non-Root Runtime
FROM eclipse-temurin:25-jdk AS runtime

# Install curl for container health checks
RUN apt-get update -qq && \
    apt-get install -y -qq --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# Run as dedicated unprivileged user
RUN groupadd -g 10001 ledger && \
    useradd -u 10001 -g ledger -m -s /bin/bash ledger

WORKDIR /app

# Copy layered files from builder preserving ownership
COPY --from=builder --chown=ledger:ledger /workspace/extracted/dependencies/ ./
COPY --from=builder --chown=ledger:ledger /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=ledger:ledger /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=ledger:ledger /workspace/extracted/application/ ./

USER 10001:10001

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=10s --timeout=5s --retries=5 --start-period=20s \
  CMD curl -fsS http://localhost:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher \"$@\"", "--"]
