# Stream c handoff – 2026-11-25
## Done this session
- C9 made mergeable – restacked as **`prod/c9-image-pipeline-restack`** (the old `prod/c9-image-pipeline` at 11ed79a is superseded, see Notes), rebased onto `prod/c8-compose-pins-tickets-db` (README conflict resolved, both sides kept), plus three fix commits: root `pom.xml` overrides (Tomcat 10.1.62, Jackson 2.21.9, PostgreSQL JDBC 42.7.15), `ui/Dockerfile` runtime-stage `apk upgrade` + nginx 1.30.8 from nginx.org, and `.github/trivyignore.yaml` with the user-accepted Boot 4-only exceptions (expiring 2027-02-28). This handoff is committed on `prod/c9-image-pipeline-restack`.
- C8 – `prod/c8-compose-pins-tickets-db` (from the previous session, unchanged).

## Ready to push
### `prod/c8-compose-pins-tickets-db` (base: `main`)
**PR title:** Compose: pinned images, tickets database, K5/K8 env vars; cache the Dockerfile package step (C8 / T5.9)

**PR body:**
Implements C8 / T5.9 of PRODUCTION_READINESS_PLAN.md. **Fixes `COMPOSE_PROFILES=java docker compose up`, which fails on `main` since B7** (the Java ticket server needs a database outside the `local` profile).

Two commits:

**1. Dockerfile: cache the package step's Maven downloads, back off on 429** (requested after the `contract (java)` 429 on the A7 PR)
- `dependency:go-offline` misses artifacts that only the package step resolves. On a fresh builder that is 15 downloads (byte-buddy, byte-buddy-agent, classmate, jackson-dataformat-yaml/-toml, hamcrest-core and their POMs). The package step had no cache mount, so it fetched them from Central on every build.
- The package step now mounts the same `/m2cache` cache (`sharing=locked`) as its local repository (`-Dmaven.repo.local=/m2cache/repository`), with the go-offline repository from the image layer as a read-only `-Dmaven.repo.local.tail`. Verified on an isolated buildx builder: 15 downloads on the first build, 0 after a source change.
- `.mvn/maven.config`: `aether.connector.http.retryHandler.interval=10000`, so the 5 retries on 429/5xx wait 10 s, 20 s, 30 s, ... instead of 5 s steps. If Central sends `Retry-After`, that wins (resolver 1.9.27, Maven 3.9.16).
- Limit: GitHub-hosted runners start with an empty cache mount, and the `type=gha` cache stores layers, not cache mounts. In CI those 15 small downloads still happen once per image build and rely on the retries. To also cache them in CI, the jobs would need something like `reproducible-containers/buildkit-cache-dance` (see "Needs from stream B").

**2. Compose (`docker-compose.yml`)**
- Every image is pinned to a version tag plus digest: `ollama/ollama:0.40.18`, `postgres:17.12-alpine`, `quay.io/keycloak/keycloak:26.8.2`, `quay.io/oauth2-proxy/oauth2-proxy:v7.15.6`. The base images are pinned too: `eclipse-temurin:21.0.13_9-jdk/-jre` (`Dockerfile`), `node:22.23.5-alpine` and `nginxinc/nginx-unprivileged:1.30.6-alpine` (`ui/Dockerfile`, up from 1.27, which no longer gets fixes). All are multi-arch (amd64 and arm64). Renovate's existing "Docker images non-major" rule updates tag and digest together. (`21.0.13.1_2` was skipped because it has no arm64 image.)
- Ollama pulls the fixed model tag `llama3.1:8b-instruct-q4_K_M` (T3.6). It has the same manifest as `llama3.1:latest`, so an existing pull is reused. `OLLAMA_MODEL` is set on both `ollama-pull` and the assistant.
- New `tickets-db` service (PostgreSQL, `java` profile only, volume `ticketsdata`, not published). The Java ticket server gets `TICKETS_DB_URL=jdbc:postgresql://tickets-db:5432/tickets`, `TICKETS_DB_USER=tickets`, `TICKETS_DB_PASSWORD` (local-only default `local-only-tickets-db-password`) and `SPRING_PROFILES_ACTIVE=local` for the demo seed, and waits for the database to be healthy. It is a separate container rather than a second database in `postgres`, so existing `pgdata` volumes need no init script, and request log and tickets stay apart as in production.
- K8 (optional, blank by default): `TICKETS_DB_MIGRATION_USER/_PASSWORD` (inert until B8 lands; blank means Flyway uses the app user), `REQUEST_LOG_DB_MIGRATION_USER/_PASSWORD`, and `REQUEST_LOG_RETENTION` (`P90D`).
- K5: `MCP_SERVICE_TOKEN` on all MCP servers, plus `TICKETS_MCP_TOKEN` and `REQUEST_LOG_MCP_TOKEN` on the assistant, from YAML anchors with local-only defaults. They are inert until B9 (servers) and A14 (assistant). **Merge A14 no later than B9**, or compose's assistant gets 401s from the servers.
- The `rust` profile is labelled legacy (D2) in compose, README and HOW_IT_WORKS: an SSE reference implementation, never deployed, and after B10/A15 only for the contract tests and MCP Inspector.
- Not wired: `OTEL_*`, `TRACING_SAMPLING_PROBABILITY`, `EMBEDDING_*` and `RATE_LIMIT_ASKS_PER_MINUTE` (K8). They have no implementation yet (A11/A16/A17), and a blank value could later mean something different from "unset". Add them with those tasks.
- README and HOW_IT_WORKS: the tickets DB, legacy Rust, pinned images and model, the new env vars.

**Verified locally** (compose project `c8test`; containers, volumes, images and the extra builder were removed afterwards):
- `docker compose config` for the `java`, `python` and `rust` profiles.
- `COMPOSE_PROFILES=java docker compose up --wait ticket-mcp-server-java request-log-mcp-server`: `tickets-db`, `postgres`, the request-log server and the Java ticket server all become healthy. The log shows profile `local` and Flyway on `jdbc:postgresql://tickets-db:5432/tickets (PostgreSQL 17.12)`. The demo seed gives 3 customers and 10 tickets.
- Contract suite against the compose Java server (port published by a test override): **65 passed**. After a server restart, the tickets created by the suite are still there (14 tickets, max T-1014).
- The UI image on nginx 1.30.6 starts, serves `/healthz` and the SPA; `nginx -v` = 1.30.6. `cd ui && npm run lint && npm test && npm run build` pass (24 tests).
- Not run: the full stack with Ollama and the model pull (about 5 GB), and the assistant against the new token env vars (inert today).

**Needs from stream B**
- Optional: persist BuildKit cache mounts in the `contract` job (e.g. `reproducible-containers/buildkit-cache-dance`), so the package step's remaining downloads are cached in CI too. The `contract` job also uses `postgres:17-alpine` unpinned; `postgres:17.12-alpine@sha256:f9cf3e1f…` would match compose.
- B9: after B9, `.mcp.json` (Claude Code) needs the service tokens; compose's local defaults are `local-only-tickets-mcp-token` and `local-only-request-log-mcp-token`.
- B8: compose already passes `TICKETS_DB_MIGRATION_USER/_PASSWORD` (blank). If B8 makes them required outside `local`, nothing changes here, because compose runs the server in `local`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### `prod/c9-image-pipeline-restack`, to be pushed as `prod/c9-image-pipeline` (base: `prod/c8-compose-pins-tickets-db`; retarget to `main` after C8 merges)
**PR title:** Image pipeline: build, Trivy scan, CycloneDX SBOM, GHCR push and cosign signing (C9 / T6.2)

**PR body:**
Implements C9 / T6.2 of PRODUCTION_READINESS_PLAN.md.

**Stacked on the C8 PR (`prod/c8-compose-pins-tickets-db`).** This PR's base is the C8 branch, so the diff shows only C9. After C8 is merged, retarget this PR to `main` (GitHub usually does that automatically when the C8 branch is deleted). It is stacked because both touch `README.md` (the Packaging row) and the `ui/Dockerfile` fix builds on C8's nginx 1.30.6 base image.

Four commits:

**1. New `.github/workflows/images.yml`**, a matrix over the deployable images: `assistant`, `ticket-mcp-server-java`, `request-log-mcp-server` (root `Dockerfile`, `MODULE=`), `ticket-mcp-server-python` and `ui`. Rust has no image (legacy, D2).
- **Every run (PRs too):** Buildx build with the GHA cache (scope `image-<name>`) and `load: true`. **Trivy** (`aquasecurity/trivy-action`, Trivy 0.70.0) fails on HIGH/CRITICAL OS and library vulnerabilities that have a fix (`ignore-unfixed`). A **CycloneDX SBOM** of the image (`anchore/sbom-action`, syft) is uploaded as an artifact `sbom-<image>.cdx.json`.
- **Push to `main` and `v*` tags only:** the same loaded image (nothing is rebuilt after the scan) is pushed to `ghcr.io/<owner>/<repo>/<image>` as `sha-<short>`, plus `1.2.3` and `1.2` on a `v1.2.3` tag. There is no `latest`. Then **cosign keyless** (`sigstore/cosign-installer`, cosign v3.1.5) signs the digest and attaches the SBOM as a signed `cyclonedx` attestation. The job summary lists digest and tags.
- Permissions: top-level `contents: read`. The image job adds `packages: write` and `id-token: write`, which only the push/sign steps use (fork PRs get neither, which is fine). Actions are pinned by SHA (login v4.8.0, metadata v6.2.0, trivy v0.36.0, sbom v0.24.4, cosign-installer v4.1.4).
- SBOM deviation from T6.2: one syft CycloneDX SBOM per image instead of `cyclonedx-maven-plugin` + syft. syft reads the Maven metadata of the jars in the image, so the Java dependencies are included. The Docker build needs no extra plugin (and no extra Central downloads).
- README: "Packaging" row with the image names, tags and the `cosign verify` / `verify-attestation` commands.

**2. Root `pom.xml`: security overrides of Boot-managed versions.** Spring Boot 3.5.16 is already the newest 3.5.x, so: `tomcat.version` 10.1.62 (fixes from 10.1.59: 5 CRITICAL, 1 HIGH), `jackson-bom.version` 2.21.9 (fixes from 2.21.7: 5 HIGH), `postgresql.version` 42.7.15 (fix from 42.7.12: HIGH). These are the newest patches on each line on Maven Central today. A comment says to drop each override once Boot manages that version or newer. `./mvnw verify` (all modules, coverage gate, format check) passes.

**3. `ui/Dockerfile`: patch the runtime stage.** The nginx-unprivileged 1.30.6 image lags behind twice: Alpine packages fixed since it was built (musl, libcrypto3/libssl3, zlib, ...), and nginx itself: nginx 1.30.7 and 1.30.8 (security releases of 18 and 23 Nov, e.g. CVE-2026-123007 in HTTP/2) are out on nginx.org, but there is no nginx-unprivileged 1.30.7+ image yet. The runtime stage now switches to root, runs `apk upgrade --no-cache`, then upgrades nginx and its modules to the newest patch of the same stable line (`nginx~1.30` etc., with `--upgrade`, since the base image pins exact versions in `/etc/apk/world`) from nginx.org's package repository, whose signing key the base image already trusts, and switches back to user 101. The image runs as uid 101, `nginx -v` = 1.30.8, `/healthz` and `/` return 200.
- Note: the upgrade happens at build time, and the GHA layer cache keeps that layer until the base image digest changes (Renovate bumps it). That is fine for the gate, since a newly published fix then shows up as a red scan and a Renovate PR fixes it.

**4. Trivy exceptions, accepted by the maintainer, with expiry.** `.github/trivyignore` becomes `.github/trivyignore.yaml` (the only format with per-package scope and a statement); the workflow and README point at it. Each entry is scoped to its package by purl, has a reason and **expires on 2027-02-28**, after which the finding fails the gate again (checked locally with a past date):

| CVE | Package | Why it cannot be fixed now |
|---|---|---|
| CVE-2026-47884 | `org.springframework:spring-webmvc` 6.2.19 | fixed only in Spring Framework 7.0.9 = Spring Boot 4.x |
| CVE-2026-47890 | `org.springframework:spring-webmvc` 6.2.19 | fixed only in Spring Framework 7.0.9 = Spring Boot 4.x |
| CVE-2026-35568 | `io.modelcontextprotocol.sdk:mcp-core` 0.18.3 | fixed in MCP Java SDK 1.0.0, only in Spring AI 2.0 (Boot 4.x); Spring AI 1.1.8 is the newest 1.1.x |
| CVE-2026-47890 | `org.springframework:spring-webflux` 6.2.19 (via `spring-ai-ollama`) | fixed only in Spring Framework 7.0.9 = Spring Boot 4.x |
| CVE-2026-47892 | `org.springframework:spring-webflux` 6.2.19 (via `spring-ai-ollama`) | fixed only in Spring Framework 7.0.9 = Spring Boot 4.x |

**Gate status (local Trivy 0.70.0, same flags and ignore file as the workflow):**
- `ticket-mcp-server-java`, `request-log-mcp-server`, `ui`: **pass** (0 findings).
- `assistant`: passes once the spring-webflux exceptions (accepted by the maintainer, same root cause as spring-webmvc) are applied; added in the last commit, not re-scanned locally after that commit.
- `ticket-mcp-server-python`: **the `python` matrix job stays red until stream B upgrades the OS packages in `ticket-mcp-server-python/Dockerfile`** (Debian `libzstd1`, HIGH, fixed in `1.5.7+dfsg-1+deb13u1`). That file is stream B's, so it is not changed here.

Until stream B fixes it, the workflow is red on `main` for the `python` image and publishes nothing for it; the other four images publish.

**Verified locally:** `./mvnw verify` (all modules); `cd ui && npm run lint && npm test && npm run build` (24 tests); the four images above built and scanned as described; the UI container smoke test; an expired exception fails the gate; actionlint 1.7.7 reports nothing for `images.yml` (it still flags SC2034 in `ci.yml`'s `contract` job, which is stream B's). Test images, containers and the scan cache were removed afterwards.

**Not verified:** a real push, signature and attestation (they need GitHub's OIDC token and GHCR, so they run only after merge). After the first `main` run, check that the packages appear under the repo, and set their visibility if needed (GHCR packages are private by default).

**Needs from stream B**
- `ticket-mcp-server-python/Dockerfile`: upgrade Debian packages in the runtime stage (at least `libzstd1` to `1.5.7+dfsg-1+deb13u1`, e.g. `apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*`), so the `python` job of `images.yml` passes.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none (C9 is complete, but its `assistant` and `python` image jobs stay red until the decision and the stream B fix described in the C9 PR body)

## Next task
C12 – dependencies satisfied: yes (A7, B7, C7 merged); it uses compose, so branch from C8 if C8 is not merged yet. C10 – no (⛓ A16). C11a [L] – no (waits for S1 / A8).

## Blocked on
nothing for C12. C9's `python` gate waits for stream B (libzstd1 in `ticket-mcp-server-python/Dockerfile`). C10 waits for A16; C11a+ wait for S1 (A8).

## Notes for the next session
- The restacked C9 lives on `prod/c9-image-pipeline-restack`. The old branch `prod/c9-image-pipeline` (11ed79a, based on `main`) could not be moved: it is checked out in the previous session's worktree `.claude/worktrees/agent-a00884f7885b99f24`, and the agent's isolation guard refuses `git update-ref`/`branch -f` on it. To restore the planned name (it was never pushed), from the main checkout: `git worktree remove .claude/worktrees/agent-a00884f7885b99f24 && git branch -f prod/c9-image-pipeline prod/c9-image-pipeline-restack && git branch -D prod/c9-image-pipeline-restack`. Or push it directly under the planned name: `git push -u origin prod/c9-image-pipeline-restack:prod/c9-image-pipeline`.
- Trivy `.trivyignore.yaml`: `purls` without a version scope an exception to one package (checked: spring-webmvc's CVE-2026-47890 is ignored while spring-webflux's is still reported). `expired_at` works (a past date fails the gate). trivy-action v0.36.0 accepts exactly one YAML file in `trivyignores`.
- nginx-unprivileged images pin nginx in `/etc/apk/world` (`nginx=1.30.6-r1`), so plain `apk upgrade` never upgrades nginx; `apk add --upgrade 'nginx~1.30'` with nginx.org's repo does.
- zsh: `$m:test` in a shell loop applies the `:t` modifier (tag became `assistantest`). Use `${m}:test`.
- The worktree-isolation guard rejects commands that mix `git` with absolute `.github` paths or pipes; split them, and copy files to the scratchpad before mounting them into containers.
- Trivy locally: `docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v <ignorefile>:/work/i.yaml:ro aquasec/trivy:0.70.0 image --scanners vuln --pkg-types os,library --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 --ignorefile /work/i.yaml <image>`.
- actionlint: `docker run --rm -v "<worktree>:/repo" -w /repo rhysd/actionlint:1.7.7`.
- The user's stack (`helpdesk-assistant-*`) runs on 5173/8080/8081/8082; a `spring-petclinic-postgres-1` container is also running and is not ours. Always use other names and ports.
- The Renovate config validator has still not been run.
