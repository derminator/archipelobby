# Build stage: plain JDK, no native-image
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /app
COPY . ./
ARG SPRING_PROFILE=prod
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILE}
RUN ./gradlew bootJar --no-daemon

# Run stage: JRE + CPython
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

RUN groupadd -r -g 10001 appuser && useradd -r -u 10001 -g appuser appuser
RUN mkdir -p /data && chown -R appuser:appuser /data

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates python3 python3-venv python3-pip python3-dev git cmake build-essential && \
    rm -rf /var/lib/apt/lists/*

# Pre-create a venv so Archipelago dependencies live in a PEP 668-clean
# location rather than the system Python installation.
RUN python3 -m venv /app/.venv && /app/.venv/bin/pip install --upgrade pip

ENV PIP_NO_CACHE_DIR=1

COPY --from=build /app/build/libs/*.jar app.jar
COPY ./Archipelago ./Archipelago
COPY ./python ./python

# Install all bundled world requirements while the image is built. This keeps
# room rendering from prompting to install dependencies on its first request.
RUN cd /app/Archipelago && /app/.venv/bin/python ModuleUpdate.py --yes

RUN chown -R appuser:appuser /app
USER appuser

# Point the runner at the venv used to install Archipelago's dependencies.
ENV ARCHIPELOBBY_PYTHON_EXECUTABLE=/app/.venv/bin/python

EXPOSE 8080 38281-38380
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=prod"]
