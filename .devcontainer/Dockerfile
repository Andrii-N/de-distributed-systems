FROM eclipse-temurin:25-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven git curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
