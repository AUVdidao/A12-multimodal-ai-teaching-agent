FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S engine && adduser -S engine -G engine
COPY --from=build /workspace/target/ppt-engine-service-0.1.0-SNAPSHOT.jar /app/app.jar
RUN chown -R engine:engine /app \
    && mkdir -p /engine-output \
    && chown engine:engine /engine-output \
    && chmod 0750 /engine-output
USER engine
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8080/internal/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
