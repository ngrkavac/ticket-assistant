# Usage: docker build --build-arg MODULE=assistant -t helpdesk-assistant .
# Base images are pinned to a version tag plus digest; Renovate updates both.

# Global, so the final stage can start from the module's runtime stage (runtime-${MODULE} below).
# The default only keeps that FROM line valid for linters; always pass --build-arg MODULE=<module>.
ARG MODULE=assistant

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

# The assistant's ONNX embedding model (all-MiniLM-L6-v2) and its tokenizer, baked into its image so
# pods need no internet at startup (T3.5). Both files are pinned to a Spring AI commit (the last one
# that touched them; they are identical to the files behind Spring AI's default URIs on `main`) and
# to their sha256 checksums. curl retries flaky downloads. Only the assistant's runtime stage uses
# this stage, so the other images never run it. The step depends only on these ARGs, so the layer
# cache (images.yml: GHA, mode=max) keeps it until one of them changes.
FROM eclipse-temurin:21.0.13_9-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1 AS onnx-model
ARG SPRING_AI_MODELS_COMMIT=82fe510b396898e0a9ed5ff0d6a0dcf0b268ebe4
ARG ONNX_MODEL_SHA256=e3dde332c13808c718680e7bf74a574e7e5d06f55bd6e1527e51509dcb8206f3
ARG TOKENIZER_SHA256=da0e79933b9ed51798a3ae27893d3c5fa4a201126cef75586296df9b4d2c62a0
WORKDIR /out/all-MiniLM-L6-v2
RUN set -eu; \
    path=models/spring-ai-transformers/src/main/resources/onnx/all-MiniLM-L6-v2; \
    fetch() { curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 --connect-timeout 20 \
      --max-time 600 -o "$1" "$2"; }; \
    fetch model.onnx \
      "https://media.githubusercontent.com/media/spring-projects/spring-ai/${SPRING_AI_MODELS_COMMIT}/${path}/model.onnx"; \
    fetch tokenizer.json \
      "https://raw.githubusercontent.com/spring-projects/spring-ai/${SPRING_AI_MODELS_COMMIT}/${path}/tokenizer.json"; \
    printf '%s  %s\n' "$ONNX_MODEL_SHA256" model.onnx "$TOKENIZER_SHA256" tokenizer.json \
      | sha256sum -c -; \
    chmod 0444 model.onnx tokenizer.json

# Spring AI's embedding model also needs DJL native libraries: PyTorch (for the pooling maths; about
# 450 MB on amd64, 275 MB on arm64) and HuggingFace tokenizers. Without this stage DJL downloads
# PyTorch from publish.djl.ai into /tmp on every container start. This stage embeds one sentence with the baked model (deploy/docker/djl/
# WarmUpEmbedding.java, using the assistant's own dependency jars), so DJL fetches exactly the files
# the runtime needs for this platform into /out/djl. The versions come from the DJL jars; the files
# are then checked against the per-architecture sha256 manifest (deploy/docker/djl/natives-<arch>.
# sha256). After a DJL/Spring AI upgrade the check fails and prints the new manifest to commit.
# The stage reruns only when the dependency jars, the program or the manifests change.
FROM eclipse-temurin:21.0.13_9-jdk@sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1 AS djl-natives
ARG TARGETARCH
COPY --from=build /out/dependencies/lib/ /deps/
COPY --from=onnx-model /out/ /opt/models/
COPY deploy/docker/djl/ /src/
RUN set -eu; \
    for i in 1 2 3; do \
      DJL_CACHE_DIR=/out/djl java -cp '/deps/*' /src/WarmUpEmbedding.java \
        file:/opt/models/all-MiniLM-L6-v2/model.onnx file:/opt/models/all-MiniLM-L6-v2/tokenizer.json \
        && break; \
      if [ "$i" -eq 3 ]; then exit 1; fi; \
      echo "DJL native download failed (attempt $i), retrying" >&2; rm -rf /out/djl; sleep $((i * 10)); \
    done; \
    cd /out/djl; \
    find . -type f | LC_ALL=C sort | xargs sha256sum > /tmp/natives.sha256; \
    if ! cmp -s /tmp/natives.sha256 "/src/natives-${TARGETARCH}.sha256"; then \
      echo "DJL natives differ from deploy/docker/djl/natives-${TARGETARCH}.sha256:" >&2; \
      diff "/src/natives-${TARGETARCH}.sha256" /tmp/natives.sha256 >&2 || true; \
      echo "If this follows a DJL/Spring AI upgrade, commit this as the new manifest:" >&2; \
      cat /tmp/natives.sha256 >&2; exit 1; \
    fi; \
    chmod -R a+rX,a-w /out/djl

# Runtime base shared by every Java module.
FROM eclipse-temurin:21.0.13_9-jre@sha256:ca73b4d3961703d6d7830a4361f49ef0478f8f28b39defc07bb72f4cc7f834f6 AS runtime-base
RUN useradd --system --uid 10001 app
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# One runtime stage per module; the final stage starts from runtime-${MODULE}, so a new Java module
# needs its own (usually empty) stage here.
# Assistant: Spring AI reads file: URIs in place (it does not copy them into its cache), so the baked
# files under /opt/models stay owned by root and read-only (0444): uid 10001 can read them but not
# change them, and a read-only root filesystem is fine. The cache dir /models is
# used only if EMBEDDING_MODEL_URI/EMBEDDING_TOKENIZER_URI are overridden with http(s) URIs.
# DJL loads its natives from the read-only /opt/djl and, being offline, never downloads.
FROM runtime-base AS runtime-assistant
COPY --from=onnx-model /out/ /opt/models/
COPY --from=djl-natives /out/djl/ /opt/djl/
RUN mkdir /models && chown 10001 /models
ENV EMBEDDING_MODEL_URI=file:/opt/models/all-MiniLM-L6-v2/model.onnx \
    EMBEDDING_TOKENIZER_URI=file:/opt/models/all-MiniLM-L6-v2/tokenizer.json \
    EMBEDDING_CACHE_DIR=/models \
    DJL_CACHE_DIR=/opt/djl \
    DJL_OFFLINE=true

FROM runtime-base AS runtime-ticket-mcp-server-java
FROM runtime-base AS runtime-request-log-mcp-server

FROM runtime-${MODULE}
COPY --from=build /out/dependencies/ ./
COPY --from=build /out/spring-boot-loader/ ./
COPY --from=build /out/snapshot-dependencies/ ./
COPY --from=build /out/application/ ./
USER 10001
ENTRYPOINT ["sh", "-c", "exec java -jar *.jar"]
