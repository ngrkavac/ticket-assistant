# Stream b handoff – 2026-11-25
## Done this session
- B12 – `prod/b12-prod-fail-fast` (base `prod/b11-observability`, stacked: B9, B11 and B12 all change the Java servers' `application-prod.properties`, `McpServiceAuthConfig` and its tests)

## Ready to push
Merge order: B9 (held until A14) → B11 → B12. (`prod/b-py-image-patch` is merged.)

### Branch `prod/b9-mcp-service-token` (base: `main`) – HELD by the user until A14 merges
**PR title:** MCP servers: service token and Origin check per K5 (B9)

**PR body:**
Implements B9 / T3.4 (server side) of PRODUCTION_READINESS_PLAN.md, contract K5.

B8 (which this branch was stacked on) is merged, so the PR diff against `main` is B9 only.

- Java (ticket server and request log): `McpServiceAuthConfig` registers a servlet filter (highest precedence) on every path except `/actuator/**`:
  - `Origin` header present and not in `MCP_ALLOWED_ORIGINS` (comma-separated, empty by default) → 403. Applies even without a token (DNS-rebinding protection); server-to-server clients send no `Origin`.
  - `MCP_SERVICE_TOKEN` set and `Authorization: Bearer <token>` missing or wrong → 401 with `WWW-Authenticate: Bearer`. Constant-time comparison; the scheme is case-insensitive.
  - Blank token: token check disabled with a startup warning; in the `prod` profile startup fails.
- Python: the same as a pure ASGI middleware (`ticket_server/auth.py`, so the SSE stream is not buffered). Rust (legacy reference, D2): the same as an axum middleware (`src/auth.rs`); `transport::router` now takes a `ServiceAuth`. Both read `MCP_SERVICE_TOKEN`/`MCP_ALLOWED_ORIGINS`; they have no profiles, so a blank token only warns.
- Contract tests: send `MCP_SERVICE_TOKEN` (if set) as a bearer token; new checks: a foreign `Origin` → 403, missing/wrong token → 401 (skipped without a token), probes need no token. CI `contract` job: every server runs with `MCP_SERVICE_TOKEN=contract-only-token` (job-level env).
- `.mcp.json`: sends `Authorization: Bearer ${TICKETS_MCP_TOKEN:-}` / `${REQUEST_LOG_MCP_TOKEN:-}` (the K5 client env names), so Claude Code keeps working against servers that have a token; with no token set, the empty header is ignored by servers without one.
- Decision to review: K5 says a blank token is allowed "only in the local profile". Here only `prod` fails fast; other non-`local` profiles warn. Reason: compose runs the request-log server without a profile, and requiring a token there would break compose until both C8 (sets the token) and A14 (assistant sends it) are merged. B12 (prod fail-fast) or a later change can tighten this once compose sets tokens.
- Verified: `./mvnw -pl request-log-mcp-server,ticket-mcp-server-java -am verify` green (84 + 62 tests, coverage, format). Python: ruff check/format + 33 tests. Rust: `cargo fmt --check`, `cargo clippy --all-targets -D warnings`, `cargo test` (35 tests; run in `rust:1-slim-trixie` via Docker, no local toolchain). Contract suite against Java jar, Python and Rust image, each with a token (68 passed) and without (67 passed, 1 skipped).

**Needs from stream A**
- A14: send `Authorization: Bearer ${TICKETS_MCP_TOKEN}` / `${REQUEST_LOG_MCP_TOKEN}` on the MCP transports. Until then, keep the servers' `MCP_SERVICE_TOKEN` unset in compose.

**Needs from stream C**
- Compose (C8): once A14 is merged, set `MCP_SERVICE_TOKEN` on each MCP server and the matching `TICKETS_MCP_TOKEN`/`REQUEST_LOG_MCP_TOKEN` on the assistant (e.g. from `.env`). Leave `MCP_ALLOWED_ORIGINS` empty; set it (e.g. `http://localhost:6274`) only for a browser-based MCP Inspector.
- Helm (C11a/b): `MCP_SERVICE_TOKEN` from a Secret per server (required in prod), the same values for the assistant; probes on `/actuator/health/*` need no token.
- `CLAUDE.md`: the contract command can take `MCP_SERVICE_TOKEN=<token>` if the server has one.

**README / docs needs (C14/C15)**
- SECURITY.md: MCP service-to-service auth (bearer token per server, Origin allow-list, probes open, rotation = change the Secret on server and assistant together). README: `TICKETS_MCP_TOKEN`/`REQUEST_LOG_MCP_TOKEN` for `.mcp.json`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### Branch `prod/b11-observability` (base: `prod/b9-mcp-service-token`)
**PR title:** MCP servers: JSON logs, tracing and /actuator/prometheus (B11)

**PR body:**
Implements B11 / T4.1, T4.2, T4.3 of PRODUCTION_READINESS_PLAN.md for the MCP servers.

**Stacked on the B9 PR (`prod/b9-mcp-service-token`)**, because both change the Java servers' `application*.properties`, `McpServiceAuthConfig` and `LeastPrivilegeTests`, and the Python `server.py`. It must merge **after B9** (which is held until A14 merges); open it with base `prod/b9-mcp-service-token`, or with base `main` once B9 is merged.

- Java (ticket server and request log): `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp` (versions from the Boot BOM).
  - Metrics (T4.3, K7, K9): `/actuator/prometheus` is exposed on the **app port** (8081/8082) next to the probes, as K7 says (no token, like all of `/actuator/**`; the NetworkPolicy keeps it internal). `http_server_requests_seconds` with histogram buckets (p95/p99 alerts), `hikaricp_*`, `jvm_*`, `requestlog_purged_rows_total`; every series is tagged `application=<server name>`. Setting `MANAGEMENT_SERVER_PORT` (standard Spring Boot) moves `/actuator/**` to its own port instead, if Helm prefers that, without changing K7's default.
  - Tracing (T4.2, K8): incoming W3C `traceparent` headers are continued; `traceId`/`spanId` are in the MDC. Sampling from `TRACING_SAMPLING_PROBABILITY` (default 0.1). `OtlpTracingConfig` exports spans over OTLP/HTTP only when `OTEL_EXPORTER_OTLP_ENDPOINT` is set and not blank, using the OpenTelemetry convention (collector base URL, `/v1/traces` appended). Boot's own `management.otlp.tracing.endpoint` would export even with an empty value.
  - JSON logs (T4.1): `logging.structured.format.console=ecs` in the `prod` profile (ECS, one object per line, MDC incl. `traceId`). No tool arguments or results (questions, answers) are logged.
  - `McpServiceAuthConfig` (B9): the filter order moves from `HIGHEST_PRECEDENCE` to `HIGHEST_PRECEDENCE + 2`, right after Spring's `ServerHttpObservationFilter`, so rejected requests (401/403) show up in `http_server_requests_seconds{status="401"}` and in traces.
  - Tests: `ObservabilityTests` (running server: prometheus without a token, 401 counted, Hikari and application tag, purge counter; a given `traceparent` ends up as the exported span's trace id), `OtlpTracingConfigTests` (no/blank endpoint → no exporter; endpoint → `…/v1/traces`), `LeastPrivilegeTests.prodLogsAreJson`.
- Python: `LOG_FORMAT=json` (default `text`) makes the server log ECS-shaped JSON lines (`@timestamp`, `log.level`, `log.logger`, `message`, `service.name`, `error.*`) on stdout, uvicorn's loggers included; an unknown value fails at startup. Standard library only, no new dependency. The unused module-level `app` is gone, so `main()` configures logging before building the app. Python has no Prometheus endpoint or tracing (in-memory demo implementation); see notes.
- Verified: `./mvnw -pl request-log-mcp-server,ticket-mcp-server-java -am verify` green (92 + 70 tests, coverage, format). Python: ruff check/format + 38 tests. Contract suite: Java jar (local profile, ECS logs on) 67 passed, 1 skipped; Python server with `LOG_FORMAT=json` 67 passed, 1 skipped (the skip is the token test, no token set).

**Needs from stream A**
- A17: propagate `traceparent` on the MCP HTTP transport (the servers continue it), and use the same `TRACING_SAMPLING_PROBABILITY`/`OTEL_EXPORTER_OTLP_ENDPOINT` semantics.
- A19: MCP panels can use `http_server_requests_seconds{application="ticket-mcp-server-java"|"request-log-mcp-server"}`, `hikaricp_connections_*{application=…}` and `requestlog_purged_rows_total`. Rejected MCP calls are `status="401"`/`"403"` with `uri="UNKNOWN"`.

**Needs from stream C**
- Helm (C11a/b): scrape `/actuator/prometheus` on 8081/8082 (or on `MANAGEMENT_SERVER_PORT` if set); env `OTEL_EXPORTER_OTLP_ENDPOINT`, `TRACING_SAMPLING_PROBABILITY`; `LOG_FORMAT=json` for the Python server if it is deployed.
- Compose (optional): nothing required; `LOG_FORMAT=json` is not needed locally.

**README / docs needs (C14/C15)**
- Runbook: MCP server metrics, the env vars above, JSON log fields.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### Branch `prod/b12-prod-fail-fast` (base: `prod/b11-observability`)
**PR title:** MCP servers: prod profile fails fast on missing required config (B12)

**PR body:**
Implements B12 / T5.8 (E6) of PRODUCTION_READINESS_PLAN.md for both Java MCP servers (request log and ticket server), per K5 and K8.

**Stacked on B11 (`prod/b11-observability`), which is stacked on B9 (`prod/b9-mcp-service-token`)**: all three change the servers' `application-prod.properties`, `McpServiceAuthConfig` and its tests. It must merge **after B9 and B11**; open it with base `prod/b11-observability`, or with base `main` once both are merged.

- New `ProdConfigGuard` (`@Profile("prod")`, a `BeanFactoryPostProcessor`, so it runs before the DataSource or Flyway touch a database). It checks every required setting and fails startup with **one** message listing all problems, so an operator fixes the Secret once instead of once per restart:
  - `REQUEST_LOG_DB_URL` / `TICKETS_DB_URL`: set and `jdbc:postgresql:`;
  - `*_DB_USER`, `*_DB_PASSWORD`: set;
  - `*_DB_MIGRATION_USER` (set, and different from `*_DB_USER`), `*_DB_MIGRATION_PASSWORD`: set;
  - `MCP_SERVICE_TOKEN`: set (K5).
  Blank values and unresolved placeholders count as missing. The message names env vars only and never echoes a value (of a URL only its scheme), e.g.:
  ```
  The prod profile is missing required configuration:
    - TICKETS_DB_URL must be set (a jdbc:postgresql: URL)
    - TICKETS_DB_PASSWORD must be set
    - MCP_SERVICE_TOKEN must be set: every MCP request must carry it as 'Authorization: Bearer <token>'
  ```
- Consistency: the prod checks that were spread over `DatabaseUrlGuard`/`TicketsDatabaseUrlGuard` (URL scheme, migration user) and `McpServiceAuthConfig` (token, B9) now all live in `ProdConfigGuard`. The URL guards keep only the "URL required" check for non-`local`, non-`prod` profiles; `McpServiceAuthConfig` only warns on a blank token (in `prod` the guard has already failed). Newly required in `prod`: `*_DB_USER`/`*_DB_PASSWORD` (before: a placeholder error later) and `*_DB_MIGRATION_PASSWORD`.
- Non-`prod` profiles are **not** stricter: without a profile (compose) only the DB URL is required, `local` needs nothing. New test `defaultProfileNeedsOnlyTheDatabaseUrl` in both servers.
- Not done on purpose: no `@ConfigurationProperties @Validated` binding. Bean validation runs when the properties bean is created, which is not guaranteed to be before the DataSource/Flyway, and it does not list everything at once. No minimum token length (K5 does not define one); OIDC issuer (T5.8) does not apply to the MCP servers (A18 covers the assistant).
- Tests: `ProdConfigGuardTests` (complete config passes; every missing setting listed in one message; migration user = app user; only the URL scheme echoed; no secret in the message; real `prod` startup fails with the full list), updated `DatabaseProfileTests` and `McpServiceAuthTests`. `LeastPrivilegeTests` still starts the full `prod` profile against Testcontainers PostgreSQL.
- Verified: `./mvnw -pl request-log-mcp-server,ticket-mcp-server-java -am verify` green (98 + 75 tests, coverage, format). Contract suite against the Java jar (`local`, with `MCP_SERVICE_TOKEN`) on port 18081: 68 passed. Both jars started with `prod` and missing settings print the list above.

**Needs from stream C**
- Helm (C11a/b): in `prod`, set all of the above per server from Secrets (`*_DB_PASSWORD`, `*_DB_MIGRATION_PASSWORD`, `MCP_SERVICE_TOKEN`) and config (`*_DB_URL`, `*_DB_USER`, `*_DB_MIGRATION_USER`); the pod fails fast with the list otherwise.

**README / docs needs (C14/C15)**
- Runbook: "startup fails with 'The prod profile is missing required configuration'" → set the listed env vars.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
B10 [L] – dependencies satisfied: no, must be coordinated with A15 (S2: merge B10, then A15 right after). Only B10 and B13 remain for stream B; B13 runs against staging after C11c (⛓ C11c).

## Blocked on
nothing for development (B9 is held for A14; B11 and B12 wait for B9 only by merge order). B10 needs A15 coordination; B13 needs C11c.

## Notes for the next session
- Branch stack: `prod/b9-mcp-service-token` (from main) → `prod/b11-observability` → `prod/b12-prod-fail-fast`. Stack B10 on `prod/b12-prod-fail-fast` if these are still unmerged.
- Prod fail-fast lives in `ProdConfigGuard` (one per Java server); add new required prod settings there, not in individual configs. `DatabaseUrlGuard`/`TicketsDatabaseUrlGuard` are now `@Profile("!local & !prod")`.
- `SpringApplicationBuilder.run()` throws the guard's `IllegalStateException` directly (no root cause), so assert on it without `.rootCause()`.
- Spring Boot tests switch Prometheus and tracing off unless the test has `@AutoConfigureObservability` (see `ObservabilityTests`).
- Rejected requests have `uri="UNKNOWN"` in metrics because the auth filter answers before handler mapping.
- Python Prometheus/tracing were left out of B11 (in-memory demo store); add `prometheus-client` and OTel ASGI middleware as a follow-up if it is deployed.
- Trivy locally: `docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v <dir with trivyignore.yaml>:/cfg:ro -v helpdesk-trivy-cache:/root/.cache aquasec/trivy:latest image --scanners vuln --pkg-types os,library --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 --ignorefile /cfg/trivyignore.yaml <image>`.
- No local Rust toolchain: run Rust checks in Docker, e.g. `docker run --rm -v "$PWD":/src -v helpdesk-cargo-registry:/usr/local/cargo/registry -w /src -e CARGO_TARGET_DIR=/tmp/target rust:1-slim-trixie sh -c "rustup component add rustfmt clippy && cargo fmt --check && cargo clippy --locked --all-targets -- -D warnings && cargo test --locked"` from `ticket-mcp-server-rust`.
- Contract tests on port 18081 (8081 is the user's compose stack): Java `java -jar ticket-mcp-server-java/target/ticket-mcp-server-java-*.jar --server.port=18081 --spring.profiles.active=local`, Python `PORT=18081 uv run --project ticket-mcp-server-python ticket-server`. Add `MCP_SERVICE_TOKEN=...` to both the server and `uv run pytest` to cover the token path. Free the port afterwards with `lsof -ti tcp:18081 | xargs kill`.
- B10: the auth filters cover every path except `/actuator/**`, so the new `/mcp` endpoint is covered automatically; the contract tests' raw-transport test and `test_browser_origin_is_rejected` post to `/mcp/message` and need updating for Streamable HTTP.
