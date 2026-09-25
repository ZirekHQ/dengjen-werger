# Build the jar first: ./gradlew bootJar
# Pinned by digest so a re-pushed tag can't change what ships; Renovate bumps it.
FROM eclipse-temurin:25-jre-alpine@sha256:2ca9adf44f5c29d28ecd26cf92d75cc0c66b7f32bfd839a4439e363a8b428af8
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY build/libs/dengjen-werger.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
