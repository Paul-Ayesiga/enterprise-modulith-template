# syntax=docker/dockerfile:1
# Builds two images from one source tree — the modulith and the edge gateway — used by
# docker/docker-compose.multi.yml to run the modulith as 3 replicas behind the gateway. Self-contained:
# no host build needed, just `docker compose ... build` (or `make multi-demo`). The gradle cache is
# mounted so rebuilds are fast; the first build downloads dependencies.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -x test :bootJar :gateway:app:bootJar \
 && cp "$(ls build/libs/*.jar | grep -v -- -plain)" /modulith.jar \
 && cp "$(ls gateway/app/build/libs/*.jar | grep -v -- -plain)" /gateway.jar

# Shared runtime: a JRE plus curl (for the container healthcheck), small serial-GC heap so three
# modulith replicas fit comfortably on a laptop Docker VM. JAVA_OPTS is overridable per compose service.
FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app.jar"]

FROM runtime AS modulith
COPY --from=build /modulith.jar /app.jar

FROM runtime AS gateway
COPY --from=build /gateway.jar /app.jar
