# syntax=docker/dockerfile:1

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /build/target/trip-tour-world.jar app.jar
USER app

# Small-container friendly JVM defaults (Render free = 512 MB). Override with JAVA_OPTS.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"
# Platforms like Render inject PORT; the app reads it (default 8080).
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
