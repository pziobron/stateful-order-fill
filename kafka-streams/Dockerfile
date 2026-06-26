# Build stage
FROM gradle:jdk25 AS builder

WORKDIR /app

# Copy Gradle wrapper and root build configuration
COPY build.gradle gradle.properties /app/
COPY gradle /app/gradle
COPY gradlew /app/

# Create a minimal settings.gradle containing only the required modules
# and configure automatic Java toolchain provisioning.
RUN cat > /app/settings.gradle <<'EOF'
plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
}

rootProject.name = 'stateful-order-fill'

include 'common'
include 'kafka-streams'
EOF

# Copy source code
COPY common /app/common
COPY kafka-streams /app/kafka-streams

# Build Kafka Streams with the default Java 25 toolchain.
# The shared common module remains targeted at Java 17.
RUN ./gradlew \
    :kafka-streams:bootJar \
    --no-daemon \
    -x test

# Runtime stage
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# RocksDB native libraries require libstdc++
RUN apk add --no-cache libstdc++

# Run as a non-root user
RUN addgroup -S appgroup \
    && adduser -S appuser -G appgroup

# Copy only the Spring Boot executable JAR
COPY --from=builder \
    /app/kafka-streams/build/libs/*.jar \
    /app/app.jar

RUN chown -R appuser:appgroup /app

USER appuser

ENTRYPOINT ["java", "-jar", "/app/app.jar"]