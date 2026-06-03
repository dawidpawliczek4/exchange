FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

COPY ../gradlew .
# nie potrzeba
#COPY gradlew.bat .
COPY ../gradle.properties .
COPY ../gradle gradle
COPY ../settings.gradle.kts .

COPY ../app/build.gradle.kts ./app/
COPY ../engine/build.gradle.kts ./engine/
COPY ../buildSrc buildSrc


RUN ./gradlew --no-daemon :app:dependencies


COPY ../app/src app/src
COPY ../engine/src engine/src
RUN ./gradlew --no-daemon :app:bootJar


FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

COPY --from=builder /build/app/build/libs/app.jar .

RUN useradd --system --uid 10001 appuser
USER appuser

ENTRYPOINT ["java", "-jar", "app.jar"]
