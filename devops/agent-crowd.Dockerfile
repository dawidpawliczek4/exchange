FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

COPY gradlew settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY contracts/build.gradle.kts contracts/
COPY engine/build.gradle.kts engine/
COPY app/build.gradle.kts app/
COPY matching-service/build.gradle.kts matching-service/
COPY agent-crowd/build.gradle.kts agent-crowd/
COPY benchmark/build.gradle.kts benchmark/
COPY e2e/build.gradle.kts e2e/
COPY frontend/build.gradle.kts frontend/
RUN ./gradlew --no-daemon :agent-crowd:dependencies

COPY contracts contracts
COPY engine engine
COPY agent-crowd agent-crowd
RUN ./gradlew --no-daemon :agent-crowd:installDist

FROM eclipse-temurin:25-jre AS runtime
RUN useradd --system --uid 10001 appuser
COPY --from=builder /build/agent-crowd/build/install/agent-crowd/ /opt/agent-crowd/
USER appuser
ENTRYPOINT ["/opt/agent-crowd/bin/agent-crowd"]
