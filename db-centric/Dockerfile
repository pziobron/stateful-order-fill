# Build the boot JAR from the repository root before building this image.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY db-centric/build/libs/db-centric.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
