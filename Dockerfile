# Build stage
FROM gradle:9.2-jdk21 AS build
WORKDIR /app

# Copy gradle files
COPY . ./

# Build the application
RUN gradle bootJar --no-daemon

# Run stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

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
