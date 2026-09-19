# Multi-stage: Maven build (core + adapters + agents only), then slim JRE runtime.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Layer the POMs first so dependency resolution is cached across source-only changes.
COPY pom.xml .
COPY core/pom.xml core/pom.xml
COPY adapters/pom.xml adapters/pom.xml
COPY agents/pom.xml agents/pom.xml
# The root reactor pom declares all four modules; Maven must be able to parse control-plane's
# pom.xml even though `-pl agents -am` never builds it.
COPY control-plane/pom.xml control-plane/pom.xml

# Only the modules the agents reactor needs (core, adapters, agents). control-plane is
# deliberately NOT built here — `-pl agents -am` resolves upstream modules only.
COPY core/src core/src
COPY adapters/src adapters/src
COPY agents/src agents/src
# Pinned grill-me/grilling skills (ADAPTIVE_GRILL_PLAN.md) — packaged via agents/pom.xml's
# .agents/skills Maven resource; the runtime still receives only the packaged JAR.
COPY .agents/skills/grill-me/SKILL.md .agents/skills/grill-me/SKILL.md
COPY .agents/skills/grilling/SKILL.md .agents/skills/grilling/SKILL.md
COPY .agents/skills/LICENSE.mattpocock .agents/skills/LICENSE.mattpocock
RUN mvn -q -pl agents -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/* \
    && git config --system --add safe.directory '*'
WORKDIR /app
COPY --from=build /workspace/agents/target/agents-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
