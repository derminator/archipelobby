# Build stage
FROM ghcr.io/graalvm/native-image-community:25 AS build
WORKDIR /app
RUN microdnf install -y findutils
COPY . ./
ARG SPRING_PROFILE=prod
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILE}
RUN ./gradlew nativeCompile --no-daemon

# Run stage
FROM debian:bookworm-slim
WORKDIR /app

RUN groupadd -r -g 1000 appuser && useradd -r -u 1000 -g appuser appuser
RUN mkdir -p /data && chown -R appuser:appuser /data

# ca-certificates for HTTPS; Python runtime is embedded via GraalPy in the native binary
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/native/nativeCompile/archipelobby .
COPY  ./Archipelago ./Archipelago

RUN chown -R appuser:appuser /app

USER appuser
EXPOSE 8080
ENTRYPOINT ["./archipelobby", "--spring.profiles.active=prod"]
