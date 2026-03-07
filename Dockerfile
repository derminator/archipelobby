# Build stage
FROM gradle:9.2-jdk21 AS build
WORKDIR /app

# Copy gradle files
COPY . ./

# Build the application
RUN gradle bootJar --no-daemon

# Run stage — GraalVM Community JDK 21 provides the Truffle framework for GraalPy polyglot support
FROM ghcr.io/graalvm/jdk-community:21
WORKDIR /app

# Install curl and tar for downloading GraalPy standalone
RUN microdnf install -y curl tar gzip findutils

# Install GraalPy standalone — provides a Python home with pip so Archipelago dependencies
# can be installed and the embedded GraalPy context (via org.graalvm.polyglot:python-community)
# can find installed packages at runtime via GRAALPY_HOME.
ARG GRAALPY_VERSION=24.2.1
RUN curl -fsSL "https://github.com/oracle/graalpython/releases/download/graal-${GRAALPY_VERSION}/graalpy-${GRAALPY_VERSION}-linux-amd64.tar.gz" \
    | tar -xz -C /opt/
ENV GRAALPY_HOME=/opt/graalpy-${GRAALPY_VERSION}-linux-amd64

# Install Python packages required by the Archipelago generator
RUN ${GRAALPY_HOME}/bin/graalpy -m pip install PyYAML

# Copy Archipelago source (submodule must be initialised before building the image)
COPY archipelago /archipelago
ENV ARCHIPELAGO_PATH=/archipelago

# Create a non-root user with predictable UID/GID
RUN groupadd -r -g 1000 appuser && useradd -r -u 1000 -g appuser appuser

# Create data directory
RUN mkdir -p /data && chown -R appuser:appuser /data

# Copy the jar from build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Change ownership
RUN chown -R appuser:appuser /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
