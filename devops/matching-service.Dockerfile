FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

COPY gradlew settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY contracts/build.gradle.kts contracts/
COPY engine/build.gradle.kts engine/
COPY app/build.gradle.kts app/
COPY matching-service/build.gradle.kts matching-service/
COPY benchmark/build.gradle.kts benchmark/
RUN ./gradlew --no-daemon :matching-service:dependencies

COPY contracts contracts
COPY engine engine
COPY matching-service matching-service
RUN ./gradlew --no-daemon :matching-service:installDist

FROM eclipse-temurin:25-jre AS runtime
RUN useradd --system --uid 10001 appuser && mkdir /data && chown appuser:appuser /data
COPY --from=builder /build/matching-service/build/install/matching-service/ /opt/matching/
WORKDIR /data
USER appuser
ENTRYPOINT ["/opt/matching/bin/matching-service"]
