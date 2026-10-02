# ---- build stage ----
FROM eclipse-temurin:25-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends maven
WORKDIR /workspace
COPY pom.xml pom.xml
COPY common common
COPY secondary secondary
COPY master/pom.xml master/pom.xml
RUN mvn -pl master -am -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:25-jdk
WORKDIR /app
COPY --from=build /workspace/secondary/target/secondary.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
