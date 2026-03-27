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
    python3 python3-pip && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/native/nativeCompile/archipelobby .
COPY --from=build /app/Archipelago ./Archipelago

# Install Archipelago Python dependencies
RUN pip3 install --no-cache-dir --break-system-packages -r Archipelago/requirements.txt

RUN chown -R appuser:appuser /app

USER appuser
EXPOSE 8080
ENTRYPOINT ["./archipelobby", "--spring.profiles.active=prod"]
