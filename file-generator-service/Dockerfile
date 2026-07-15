FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S generator && adduser -S generator -G generator
WORKDIR /app
COPY --from=build /workspace/target/file-generator-service-0.1.0-SNAPSHOT.jar /app/app.jar
USER generator
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
