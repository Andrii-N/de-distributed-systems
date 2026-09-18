# syntax=docker/dockerfile:1

# ---- build stage ----
FROM eclipse-temurin:25-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY pom.xml pom.xml
COPY common common
COPY secondary secondary
COPY master/pom.xml master/pom.xml

RUN mvn -q -pl secondary -am -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:25-jdk
WORKDIR /app
COPY --from=build /workspace/secondary/target/secondary.jar app.jar

ENV PORT=8080
ENV REPLICATION_DELAY_MS=0
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
