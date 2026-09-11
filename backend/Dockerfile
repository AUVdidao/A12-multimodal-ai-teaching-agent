FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml .

COPY src ./src
RUN --mount=type=cache,id=a12-stage34-backend-m2,target=/root/.m2/repository,sharing=locked \
    mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=dev

# The template-processing contract requires a real Office-compatible renderer
# for production-style preview verification. Keep the binary in the image and
# enable it only through the explicit runtime property in compose; the Java
# adapter still fails closed when that property is empty or the process fails.
RUN apk add --no-cache libreoffice font-noto-cjk

COPY --from=build /workspace/target/*.jar /app/app.jar

RUN addgroup -S spring \
    && adduser -S spring -G spring \
    && mkdir -p /app/data \
    && chown -R spring:spring /app

USER spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
