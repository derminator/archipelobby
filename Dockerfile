# Build stage
FROM ghcr.io/graalvm/native-image-community:21 AS build
WORKDIR /app
RUN microdnf install -y findutils
COPY . ./
RUN ./gradlew nativeCompile --no-daemon

# Run stage
FROM debian:bookworm-slim

WORKDIR /app

RUN groupadd -r -g 1000 appuser && useradd -r -u 1000 -g appuser appuser
RUN mkdir -p /data && chown -R appuser:appuser /data

COPY --from=build /app/build/native/nativeCompile/archipelobby .
RUN chown appuser:appuser /app/archipelobby

USER appuser
EXPOSE 8080
ENTRYPOINT ["./archipelobby", "--spring.profiles.active=prod"]
