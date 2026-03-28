# Build stage
FROM ghcr.io/graalvm/native-image-community:25 AS build
WORKDIR /app
RUN microdnf install -y findutils
COPY . ./
ARG SPRING_PROFILE=prod
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILE}
RUN ./gradlew nativeCompile --no-daemon

# Fetch Archipelago at the pinned submodule commit
FROM debian:bookworm-slim AS archipelago-fetch
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates && rm -rf /var/lib/apt/lists/*
ARG ARCHIPELAGO_COMMIT=116ab2286ad95fe4a43fbc06247d4f0ba42e6e34
RUN mkdir /archipelago && \
    cd /archipelago && \
    git init && \
    git remote add origin https://github.com/ArchipelagoMW/Archipelago.git && \
    git fetch --depth=1 origin "${ARCHIPELAGO_COMMIT}" && \
    git checkout FETCH_HEAD

# Run stage
FROM debian:bookworm-slim
WORKDIR /app

RUN groupadd -r -g 1000 appuser && useradd -r -u 1000 -g appuser appuser
RUN mkdir -p /data && chown -R appuser:appuser /data

# Install Python 3 for the Archipelago generator
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-pip && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/native/nativeCompile/archipelobby .
COPY --from=archipelago-fetch /archipelago ./Archipelago

# Install Archipelago Python dependencies
RUN pip3 install --no-cache-dir --break-system-packages -r Archipelago/requirements.txt

RUN chown -R appuser:appuser /app

USER appuser
EXPOSE 8080
ENTRYPOINT ["./archipelobby", "--spring.profiles.active=prod"]
