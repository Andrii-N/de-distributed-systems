# syntax=docker/dockerfile:1

# ---- build stage ----
FROM eclipse-temurin:25-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY pom.xml pom.xml
COPY common common
COPY master master
COPY secondary/pom.xml secondary/pom.xml

RUN mvn -q -pl master -am -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:25-jdk
WORKDIR /app
COPY --from=build /workspace/master/target/master.jar app.jar

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
