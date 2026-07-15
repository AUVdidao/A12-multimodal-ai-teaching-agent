FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=build /workspace/target/*.jar /app/app.jar

RUN addgroup -S parser && adduser -S parser -G parser
USER parser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
