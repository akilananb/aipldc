FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY core/pom.xml core/pom.xml
COPY adapters/pom.xml adapters/pom.xml
COPY control-plane/pom.xml control-plane/pom.xml
COPY agents/pom.xml agents/pom.xml
RUN mvn -q -pl control-plane -am dependency:go-offline
COPY core core
COPY adapters adapters
COPY control-plane control-plane
RUN mvn -q -pl control-plane -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends git nodejs npm && rm -rf /var/lib/apt/lists/* \
    && git config --system --add safe.directory '*'
WORKDIR /app
COPY --from=build /build/control-plane/target/control-plane.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
