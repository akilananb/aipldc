# Restaurant-demo-only variant of control-plane/Dockerfile: identical Maven build stage, but
# installs Node.js from NodeSource instead of Debian's apt package. Debian's `nodejs` package
# ships without the bundled "amaro" TypeScript-stripping module, so `node --experimental-strip-types`
# fails with ERR_NO_TYPESCRIPT inside LocalCiAdapter's `runVerify`/`runDeploy` (git worktree +
# `npm test`) even though the same command works fine on a real Node.js build. NodeSource
# mirrors the official upstream binaries, which do include it. Never edit the shared
# control-plane/Dockerfile for this — this file is isolated to infra/restaurant/.
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
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gnupg git \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && git config --system --add safe.directory '*'
WORKDIR /app
COPY --from=build /build/control-plane/target/control-plane.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
