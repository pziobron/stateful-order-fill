# Build stage
FROM gradle:jdk25 AS builder
WORKDIR /app

# Copy build configuration
COPY build.gradle gradle.properties /app/
COPY gradle /app/gradle
COPY gradlew /app/

# Create minimal settings.gradle (only modules we need)
RUN echo "rootProject.name = 'stateful-order-fill'" > /app/settings.gradle && \
    echo "include 'common'" >> /app/settings.gradle && \
    echo "include 'kafka-streams'" >> /app/settings.gradle

# Copy source code
COPY common /app/common
COPY kafka-streams /app/kafka-streams

# Build the application
RUN ./gradlew :kafka-streams:bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Install libstdc++ required by RocksDB for Kafka Streams state stores
RUN apk add --no-cache libstdc++

# Create non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy the built JAR
COPY --from=builder /app/kafka-streams/build/libs/*.jar app.jar

# Change ownership to non-root user
RUN chown -R appuser:appgroup /app
USER appuser

# Run the application
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
