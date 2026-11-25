# Stream b handoff – 2026-11-25
## Done this session
- B7 [L] – `prod/b7-ticket-postgres` (base `main` @ d704c75)

## Ready to push

### Branch `prod/b7-ticket-postgres` (base: `main`)
**PR title:** Ticket server: store tickets in PostgreSQL with Flyway (B7)

**PR body:**
Implements B7 / T2.1 of PRODUCTION_READINESS_PLAN.md (D1: own Postgres).

**Heads-up: after this merges, `COMPOSE_PROFILES=java docker compose up` fails until compose gives the Java ticket server a database or the `local` profile (C8, see "Needs from stream C").** The server now refuses to start without `TICKETS_DB_URL` outside the `local` profile.

- `TicketTools` now reads and writes through a JDBC `TicketRepository` (`JdbcClient`) instead of in-memory maps. The tool contract is unchanged: same tools, schemas, JSON field order, validation order and error messages.
- Flyway `db/migration/V1__tickets.sql`:
  - `customer` (lower-case id, CHECK);
  - `ticket` with status/priority CHECK constraints, `created_by` (NULL for now: the tools do not receive the caller's identity) and `created_at`;
  - `ticket_number_seq` (starts at 1001) for global `T-<n>` ids. Concurrent creates, also from other replicas, get unique ids without a lock.
  - `subject` is `VARCHAR(400)`: `createTicket` enforces 200 code points, and H2 counts UTF-16 chars.
- Demo data (acme/globex/initech, T-1001…T-1010, the next ticket is T-1011) is a separate migration `db/seed/V1_1__demo_seed.sql`. Only the `local` profile includes it (`spring.flyway.locations`). `prod` and the default profile get the schema only, and an operator adds customers (`INSERT INTO customer (id) VALUES ('acme')`); there is no tool for that.
- Profiles (K8): `local` = in-memory H2 + demo seed, and `TICKETS_DB_URL`/`_USER`/`_PASSWORD` still win if set, which is how CI runs the contract tests on PostgreSQL. Otherwise `TICKETS_DB_URL` is required (`TicketsDatabaseUrlGuard` fails startup), and `prod` must use a `jdbc:postgresql:` URL. Readiness now includes `db`.
- A database error reaches the MCP client as "Ticket database is unavailable, please try again later". SQL and connection details are only logged, because the model sees tool errors.
- Tests: the old tool suite runs twice, as `PostgresTicketToolsTests` (Testcontainers `postgres:17-alpine`) and `H2TicketToolsTests` (the `local` database). Each test starts from a clean, migrated and seeded schema. There are also schema-constraint checks, `DatabaseProfileTests` (fail-fast without a URL, prod rejects H2, local has demo data, prod on PostgreSQL has no seed and starts at T-1001) and `DatabaseFailureTests`.
- CI, `contract` job: the Java image now runs with `SPRING_PROFILES_ACTIVE=local` against a `postgres:17-alpine` container on a Docker network. Python and Rust are unchanged. `ticket-mcp-contract-tests/README.md` says how to start the Java server for the suite.
- Verified: `./mvnw -pl ticket-mcp-server-java -am verify` is green (64 tests, coverage and format pass; needs Docker). Contract suite: 65 passed against the jar on PostgreSQL (`local` profile, also after a restart on the same DB), against the jar on H2, and against the Docker image run exactly as in the new CI job.

**Needs from stream C**
- Compose (C8): the `ticket-mcp-server-java` service needs a database. Suggested: a `tickets` database on the existing Postgres (or a second Postgres), plus `TICKETS_DB_URL=jdbc:postgresql://postgres:5432/tickets`, `TICKETS_DB_USER` and `TICKETS_DB_PASSWORD`, and `SPRING_PROFILES_ACTIVE=local` while compose should have the demo tickets. Until C8, a minimal fix is `environment: {SPRING_PROFILES_ACTIVE: local}` on that service (in-memory H2 with demo data, as before). `TICKETS_DB_MIGRATION_USER`/`_PASSWORD` (K8) arrive with B8.
- Helm (C11a): the same env vars for the Java ticket server, with no `local` profile in staging/prod. Customers must be inserted by an operator or a seed Job.

**README / docs needs (C14/C15)**
- Tickets are persisted in PostgreSQL (`tickets` database). Demo data only in the `local` profile. Adding a customer: `INSERT INTO customer (id) VALUES ('<lower-case id>')`. Runbook: the tool error "Ticket database is unavailable" means the ticket server cannot reach its DB; the server log has the cause.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
B8 – dependencies satisfied: yes (needs B7 merged, or branch from `prod/b7-ticket-postgres` and say so in the PR text).

## Blocked on
nothing

## Notes for the next session
- B8 can copy the request-log patterns: `MigrationUserConfig` (B4, separate Flyway user + grants callback) and `DatabaseTimeoutsConfig` (B6). Add `TICKETS_DB_MIGRATION_USER`/`_PASSWORD` (K8), a prod check in `TicketsDatabaseUrlGuard`, and Hikari timeouts in `ticket-mcp-server-java/src/main/resources/application.properties`. The app user needs USAGE on `ticket_number_seq` too (nextval), not just DML on the tables.
- Ticket test isolation: `AbstractTicketToolsTests` runs `flyway.clean()` + `migrate()` before each test (`spring.flyway.clean-disabled=false` only in the test). `H2TicketToolsTests` uses its own H2 DB name so it does not clash with `DatabaseProfileTests` starting the `local` profile.
- Port 8081 on this machine was taken by the user's running compose stack. Run the contract tests against another port, e.g. `java -jar ticket-mcp-server-java/target/*.jar --server.port=18081 --spring.profiles.active=local`, then `TICKETS_MCP_URL=http://localhost:18081 uv run pytest`.
- The contract tests create tickets, so a persistent DB keeps growing; they still pass on re-runs (checked).
- Format before verify: `./mvnw -q -B com.spotify.fmt:fmt-maven-plugin:format -pl ticket-mcp-server-java`.
