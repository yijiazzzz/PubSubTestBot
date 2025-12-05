# Use the official maven/Java 17 image to create a build artifact.
FROM maven:3.9.5-eclipse-temurin-17 AS builder

# Copy local code to the container image.
WORKDIR /app
COPY pom.xml .

# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN mvn dependency:go-offline

COPY src ./src

# Build a release artifact.
RUN mvn package -DskipTests

# Use Eclipse Temurin for base image.
FROM eclipse-temurin:17-jre-alpine

# Copy the jar to the production image from the builder stage.
COPY --from=builder /app/target/*.jar /app.jar

# Run the web service on container startup.
CMD ["java", "-jar", "/app.jar"]
