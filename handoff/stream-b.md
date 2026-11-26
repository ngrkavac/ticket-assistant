# Stream b handoff – 2026-11-25
## Done this session
- B8 – `prod/b8-ticket-db-users` (base `main` @ 4dc972d)
- B9 – `prod/b9-mcp-service-token` (base `prod/b8-ticket-db-users`, stacked: both change `ticket-mcp-server-java/src/main/resources/application*.properties` and the ticket server's prod tests)

## Ready to push

### Branch `prod/b8-ticket-db-users` (base: `main`)
**PR title:** Ticket server: least-privilege DB users and timeouts (B8)

**PR body:**
Implements B8 / T2.3 + T2.6 of PRODUCTION_READINESS_PLAN.md for the Java ticket server, the same way B4/B6 did for the request log.

- `MigrationUserConfig`: when `TICKETS_DB_MIGRATION_USER`/`_PASSWORD` (K8) are set, Flyway connects as that user (DDL, owns the tables and `ticket_number_seq`). After every migrate on PostgreSQL it grants the application user (`TICKETS_DB_USER`) `SELECT, INSERT, UPDATE, DELETE` on all tables except `flyway_schema_history`, and `USAGE, SELECT` on all sequences (so `createTicket` can call `nextval`, but cannot `setval`). Without a migration user (e.g. `local` on H2) nothing changes.
- `TicketsDatabaseUrlGuard`: in `prod`, a migration user is required and must differ from the app user; startup fails before any DB access otherwise.
- `DatabaseTimeoutsConfig` + `application.properties`: Hikari `maximum-pool-size=${TICKETS_DB_POOL_SIZE:5}`, `connection-timeout=5000`, `validation-timeout=2000`; for `jdbc:postgresql:` URLs every pooled connection gets `statement_timeout` from `TICKETS_DB_STATEMENT_TIMEOUT` (default `5s`, `0` = off) plus a driver `socketTimeout` 10 s longer. An `options` setting already in the URL wins. Flyway with its own user connects outside the pool, so long migrations are not cut off.
- Tests: `LeastPrivilegeTests` (prod profile on Testcontainers `postgres:17-alpine` with `tickets_migrator`/`tickets_app` roles from `least-privilege-roles.sql`): app user runs every tool, migrator owns tables and sequence, app user cannot CREATE/DROP/ALTER/TRUNCATE, read Flyway history or `setval`; pool size, connection timeout and a cancelled `pg_sleep`. `DatabaseTimeoutsTests` (unit). `DatabaseProfileTests`: prod needs a distinct migration user; the prod-on-PostgreSQL test now uses the two roles.
- Verified: `./mvnw -pl ticket-mcp-server-java -am verify` green (77 tests, coverage, format). Contract suite: 65 passed against the jar (`local` profile, H2).

**Needs from stream C**
- Compose (C8): when the tickets DB is PostgreSQL, optionally a separate migration role; with the `local` profile `TICKETS_DB_MIGRATION_USER` may stay unset. New optional env vars: `TICKETS_DB_POOL_SIZE`, `TICKETS_DB_STATEMENT_TIMEOUT`.
- Helm (C11a/C11b): `TICKETS_DB_MIGRATION_USER`/`_PASSWORD` (required in prod) from a Secret; a DBA creates the two roles once: `CREATE ROLE tickets_migrator LOGIN ...; CREATE ROLE tickets_app LOGIN ...; GRANT CREATE, USAGE ON SCHEMA public TO tickets_migrator; REVOKE CREATE ON SCHEMA public FROM PUBLIC;`. Operators inserting customers should use the migrator (or a DBA role); the app role can INSERT too.

**README / docs needs (C14/C15)**
- Runbook: ticket DB users (migrator = DDL, app = DML + sequence usage), the role setup above, and the timeout env vars.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### Branch `prod/b9-mcp-service-token` (base: `prod/b8-ticket-db-users`)
**PR title:** MCP servers: service token and Origin check per K5 (B9)

**PR body:**
Implements B9 / T3.4 (server side) of PRODUCTION_READINESS_PLAN.md, contract K5.

**Stacked on #<B8 PR> (`prod/b8-ticket-db-users`)**, because both change the ticket server's `application*.properties` and its prod-profile tests. Merge B8 first; this PR's diff then shrinks to B9 only.

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

## In progress
none

## Next task
B10 [L] – dependencies satisfied: needs coordination with A15 (S2: merge B10, then A15 right after). Can be developed now on its own branch. If B10 is not wanted yet, B11 (no ⛓) is next.

## Blocked on
nothing

## Notes for the next session
- B9 is stacked on B8. If B8 is merged first, rebase or just merge `main` into B9 before the user opens its PR.
- No local Rust toolchain: run Rust checks in Docker, e.g. `docker run --rm -v "$PWD":/src -v helpdesk-cargo-registry:/usr/local/cargo/registry -w /src -e CARGO_TARGET_DIR=/tmp/target rust:1-slim-trixie sh -c "rustup component add rustfmt clippy && cargo fmt --check && cargo clippy --locked --all-targets -- -D warnings && cargo test --locked"` from `ticket-mcp-server-rust`.
- Contract tests on port 18081 (8081 is the user's compose stack): Java `java -jar ticket-mcp-server-java/target/ticket-mcp-server-java-*.jar --server.port=18081 --spring.profiles.active=local`, Python `PORT=18081 uv run --project ticket-mcp-server-python ticket-server`, Rust `docker run -p 18081:8081 <image>`. Add `MCP_SERVICE_TOKEN=...` to both the server and `uv run pytest` to cover the token path. `uv run` for the Python server leaves the child process running after `kill`; free the port with `lsof -ti tcp:18081 | xargs kill`.
- The ticket server's prod tests (`LeastPrivilegeTests`, `DatabaseProfileTests`) now also need `MCP_SERVICE_TOKEN` (after B9).
- B10: the auth filters cover every path except `/actuator/**`, so the new `/mcp` endpoint is covered automatically; the contract tests' raw-transport test and `test_browser_origin_is_rejected` post to `/mcp/message` and need updating for Streamable HTTP.
