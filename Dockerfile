# JVM build stage
FROM gradle:9.2-jdk21 AS jvm-build
WORKDIR /app
COPY . ./
RUN gradle bootJar --no-daemon

# Native image build stage
FROM ghcr.io/graalvm/native-image-community:21 AS native-build
WORKDIR /app
COPY . ./
RUN ./gradlew nativeCompile --no-daemon

# JVM run stage
FROM eclipse-temurin:21-jre-jammy AS jvm

WORKDIR /app

RUN groupadd -r -g 1000 appuser && useradd -r -u 1000 -g appuser appuser
RUN mkdir -p /data && chown -R appuser:appuser /data

COPY --from=jvm-build /app/build/libs/*.jar app.jar
RUN chown -R appuser:appuser /app

USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]

# Native run stage (default)
FROM debian:bookworm-slim

WORKDIR /app

RUN groupadd -r -g 1000 appuser && useradd -r -u 1000 -g appuser appuser
RUN mkdir -p /data && chown -R appuser:appuser /data

COPY --from=native-build /app/build/native/nativeCompile/archipelobby .
RUN chown appuser:appuser /app/archipelobby

USER appuser
EXPOSE 8080
ENTRYPOINT ["./archipelobby", "--spring.profiles.active=prod"]
