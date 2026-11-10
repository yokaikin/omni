# syntax=docker/dockerfile:1.7
FROM maven:3.9.16-eclipse-temurin-17 AS build

ARG SERVICE_MODULE
WORKDIR /workspace

COPY java/pom.xml java/pom.xml
COPY java/java-common/pom.xml java/java-common/pom.xml
COPY java/java-ai-core/pom.xml java/java-ai-core/pom.xml
COPY java/java-gateway/pom.xml java/java-gateway/pom.xml
COPY java/java-user/pom.xml java/java-user/pom.xml
COPY java/java-ticket/pom.xml java/java-ticket/pom.xml
COPY java/java-order/pom.xml java/java-order/pom.xml
COPY java/java-payment/pom.xml java/java-payment/pom.xml
COPY java/java-notification/pom.xml java/java-notification/pom.xml
COPY java java

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -f java/pom.xml -pl "${SERVICE_MODULE}" -am package -Dmaven.test.skip=true \
    && cp java/${SERVICE_MODULE}/target/*.jar /workspace/app.jar

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
