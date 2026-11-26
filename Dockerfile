# Usage: docker build --build-arg MODULE=assistant -t helpdesk-assistant .
# Base images are pinned to a version tag plus digest; Renovate updates both.

# Optional pre-filled Maven home (`repository/` and the wrapper's `wrapper/`). Empty by default, so a
# plain `docker build` or compose downloads from Maven Central as usual. CI resolves dependencies once
# per pom change on the runner, keeps them in the GitHub Actions cache (.github/actions/maven-seed)
# and overrides this stage with `--build-context maven-seed=<dir>`, so parallel image builds do not
# each download the whole dependency set again (Maven Central answers that with 429).
FROM scratch AS maven-seed

FROM eclipse-temurin:21.0.13_9-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1 AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# The wrapper's download of Maven itself has no retry (.mvn/maven.config only applies once Maven
# runs), so retry it here, unless the seed already has it. It lands in its own layer, which CI
# restores from the layer cache.
RUN --mount=type=bind,from=maven-seed,target=/seed \
    if [ -d /seed/wrapper ]; then mkdir -p /root/.m2 && cp -a /seed/wrapper /root/.m2/; fi \
 && for i in 1 2 3 4 5; do ./mvnw -q -B -v && exit 0; \
      echo "Maven wrapper download failed (attempt $i), retrying" >&2; sleep $((i * 5)); done; exit 1
COPY ticket-mcp-server-java/pom.xml ticket-mcp-server-java/
COPY request-log-mcp-server/pom.xml request-log-mcp-server/
COPY assistant/pom.xml assistant/
# Dependencies are resolved into a BuildKit cache mount, so a pom change on the same builder only
# downloads what is new. A seed (see maven-seed above) is copied into the cache mount first, so only
# what it lacks is downloaded. They are then copied into this layer as well: CI restores layers from
# its layer cache but not cache mounts, and the package step must find them there. Flaky downloads
# are retried briefly (.mvn/maven.config: 3 times after 5 s, 10 s, 15 s; a 429's Retry-After header
# is honoured up to 30 s), so a rate-limited build fails in minutes instead of retrying for long.
# `locked` stops parallel image builds writing the same repository.
RUN --mount=type=cache,target=/m2cache,sharing=locked \
    --mount=type=bind,from=maven-seed,target=/seed \
    mkdir -p /m2cache/repository /root/.m2/repository \
 && if [ -d /seed/repository ]; then \
      echo "maven-seed: $(find /seed/repository -name '*.pom' | wc -l) POMs copied into the local repository"; \
      cp -a /seed/repository/. /m2cache/repository/; fi \
 && ./mvnw -q -B -Dmaven.repo.local=/m2cache/repository dependency:go-offline \
 && cp -a /m2cache/repository/. /root/.m2/repository/
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

FROM eclipse-temurin:21.0.13_9-jre@sha256:ca73b4d3961703d6d7830a4361f49ef0478f8f28b39defc07bb72f4cc7f834f6
RUN useradd --system --uid 10001 app && mkdir /models && chown 10001 /models
WORKDIR /app
COPY --from=build /out/dependencies/ ./
COPY --from=build /out/spring-boot-loader/ ./
COPY --from=build /out/snapshot-dependencies/ ./
COPY --from=build /out/application/ ./
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" EMBEDDING_CACHE_DIR=/models
ENTRYPOINT ["sh", "-c", "exec java -jar *.jar"]
