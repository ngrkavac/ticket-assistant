# Usage: docker build --build-arg MODULE=assistant -t helpdesk-assistant .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# The wrapper's download of Maven itself has no retry (.mvn/maven.config only applies once Maven
# runs), so retry it here. It lands in its own layer, which CI restores from the layer cache.
RUN for i in 1 2 3 4 5; do ./mvnw -q -B -v && exit 0; \
      echo "Maven wrapper download failed (attempt $i), retrying" >&2; sleep $((i * 5)); done; exit 1
COPY ticket-mcp-server-java/pom.xml ticket-mcp-server-java/
COPY request-log-mcp-server/pom.xml request-log-mcp-server/
COPY assistant/pom.xml assistant/
# Dependencies are resolved into a BuildKit cache mount, so a pom change on the same builder only
# downloads what is new. They are then copied into this layer as well: CI restores layers from its
# layer cache but not cache mounts, and the package step must find them there. Flaky downloads are
# retried (.mvn/maven.config: up to 5 times after 10 s, 20 s, ...; a 429's Retry-After header wins).
# `locked` stops parallel image builds writing the same repository.
RUN --mount=type=cache,target=/m2cache,sharing=locked \
    ./mvnw -q -B -Dmaven.repo.local=/m2cache/repository dependency:go-offline \
 && mkdir -p /root/.m2 && cp -a /m2cache/repository /root/.m2/repository
COPY . .
ARG MODULE
# go-offline does not fetch everything the build needs (e.g. some plugin dependencies and POMs
# that only the package step resolves). This step therefore uses the same cache mount: whatever it
# still has to download lands in /m2cache and is reused by the next build on this builder. The
# repository copied into the image layer above is a read-only "tail", so a builder whose cache
# mount is empty (a fresh CI runner) still finds everything go-offline fetched.
# Pick the single executable jar for any version (SNAPSHOT or release), skipping
# plain/sources/javadoc jars. Fail loudly if there is not exactly one candidate.
RUN --mount=type=cache,target=/m2cache,sharing=locked \
    ./mvnw -q -B -DskipTests -Dmaven.repo.local=/m2cache/repository \
      -Dmaven.repo.local.tail=/root/.m2/repository -pl ${MODULE} -am package \
 && jars=$(find ${MODULE}/target -maxdepth 1 -name "${MODULE}-*.jar" \
      ! -name '*-plain.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar') \
 && count=$(printf '%s\n' "$jars" | grep -c . || true) \
 && if [ "$count" -ne 1 ]; then echo "Expected exactly one jar in ${MODULE}/target, found $count: $jars" >&2; exit 1; fi \
 && java -Djarmode=tools -jar "$jars" extract --layers --destination /out

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app && mkdir /models && chown 10001 /models
WORKDIR /app
COPY --from=build /out/dependencies/ ./
COPY --from=build /out/spring-boot-loader/ ./
COPY --from=build /out/snapshot-dependencies/ ./
COPY --from=build /out/application/ ./
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" EMBEDDING_CACHE_DIR=/models
ENTRYPOINT ["sh", "-c", "exec java -jar *.jar"]
