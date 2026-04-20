# Build stage: plain JDK, no native-image
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY . ./
ARG SPRING_PROFILE=prod
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILE}
RUN ./gradlew bootJar --no-daemon

# Run stage: JRE + CPython
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd -r -g 10001 appuser && useradd -r -u 10001 -g appuser appuser
RUN mkdir -p /data && chown -R appuser:appuser /data

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates python3 python3-venv python3-pip && \
    rm -rf /var/lib/apt/lists/*

# Pre-create an empty venv so ModuleUpdate.py's pip install lands in a
# writable, PEP 668-clean location. Archipelago deps install on first run.
RUN python3 -m venv /app/.venv && /app/.venv/bin/pip install --upgrade pip

COPY --from=build /app/build/libs/*.jar app.jar
COPY ./Archipelago ./Archipelago

RUN chown -R appuser:appuser /app
USER appuser

# Point the runner at the pre-created venv's python so ModuleUpdate.py's
# pip installs land in /app/.venv (writable by appuser, PEP 668 clean).
ENV ARCHIPELOBBY_PYTHON_EXECUTABLE=/app/.venv/bin/python

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
