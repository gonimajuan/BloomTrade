# BloomTrade — Backend (Spring Boot 3 + Java 21)
# Multi-stage: builder con Maven → runtime JRE alpine.

# ─── Stage 1: builder ─────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Cache de dependencias separado del código fuente para acelerar rebuilds
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests \
    && mv target/*.jar /app.jar

# ─── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# curl para healthcheck; usuario no-root por seguridad
RUN apk add --no-cache curl \
    && addgroup -S bloomtrade \
    && adduser -S -G bloomtrade bloomtrade

COPY --from=builder --chown=bloomtrade:bloomtrade /app.jar /app/app.jar
USER bloomtrade

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
