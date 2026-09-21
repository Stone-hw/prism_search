# ---------- Build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Prime dependency cache
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests clean package

# ---------- Runtime stage ----------
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="mysearch" \
      org.opencontainers.image.description="Multi-provider meta search engine"

# Non-root user
RUN groupadd --system --gid 1001 mysearch \
    && useradd --system --uid 1001 --gid mysearch --home-dir /app --create-home mysearch

WORKDIR /app
COPY --from=build /workspace/target/mysearch.jar /app/app.jar

RUN chown -R mysearch:mysearch /app
USER mysearch

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=20s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
