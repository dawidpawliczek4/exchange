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
RUN ./gradlew --no-daemon :app:dependencies

COPY contracts contracts
COPY app app
RUN ./gradlew --no-daemon :app:bootJar

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN useradd --system --uid 10001 appuser
USER appuser
COPY --from=builder /build/app/build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
