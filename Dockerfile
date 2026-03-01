# Build stage
FROM gradle:9.2-jdk21 AS build
WORKDIR /app
COPY . ./
RUN gradle bootJar --no-daemon

# Native image build stage
FROM ghcr.io/graalvm/native-image-community:21 AS native-build
WORKDIR /app
COPY . ./
RUN ./gradlew nativeCompile --no-daemon

# Native run stage (opt-in: docker build --target native .)
FROM debian:bookworm-slim AS native

WORKDIR /app

RUN groupadd -r -g 1000 appuser && useradd -r -u 1000 -g appuser appuser
RUN mkdir -p /data && chown -R appuser:appuser /data

COPY --from=native-build /app/build/native/nativeCompile/archipelobby .
RUN chown appuser:appuser /app/archipelobby

USER appuser
EXPOSE 8080
ENTRYPOINT ["./archipelobby", "--spring.profiles.active=prod"]

# JVM run stage (default: docker build .)
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd -r -g 1000 appuser && useradd -r -u 1000 -g appuser appuser
RUN mkdir -p /data && chown -R appuser:appuser /data

COPY --from=build /app/build/libs/*.jar app.jar
RUN chown -R appuser:appuser /app

USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
