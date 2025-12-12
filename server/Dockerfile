FROM gradle:jdk17 AS builder
WORKDIR /app
COPY . .

# Download Gradle 8.5 manually to /tmp because gradle-wrapper.properties expects it there
RUN wget -q https://services.gradle.org/distributions/gradle-8.5-bin.zip -P /tmp

RUN ./gradlew :core:bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/core/build/libs/*.jar app.jar
CMD ["java", "-jar", "app.jar"]

