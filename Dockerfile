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

# Install Python 3 for the Archipelago generator
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-pip git ca-certificates && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/native/nativeCompile/archipelobby .
COPY  ./Archipelago ./Archipelago

# Install Archipelago Python dependencies via ModuleUpdate (mirrors how Archipelago
# manages its own deps; failures on platform-incompatible packages don't abort the build)
RUN python3 Archipelago/ModuleUpdate.py --yes

# Skip the runtime dependency check since deps are pre-installed at build time
ENV SKIP_REQUIREMENTS_UPDATE=1

RUN chown -R appuser:appuser /app

USER appuser
EXPOSE 8080
ENTRYPOINT ["./archipelobby", "--spring.profiles.active=prod"]
