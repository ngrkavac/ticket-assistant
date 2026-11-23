# Stream b handoff – 2026-11-23
## Done this session
- B3 – `prod/b3-user-subject-tool-calls` (base `main` @ 2f200f7)
- B4 – `prod/b4-request-log-db-users` (base `prod/b3-user-subject-tool-calls`, stacked)

## Ready to push

### Branch `prod/b3-user-subject-tool-calls` (base: `main`)
**PR title:** Request log: store user subject and tool calls per K4 (B3)

**PR body:**
Implements B3 / T1.5, T1.6 (server side) of PRODUCTION_READINESS_PLAN.md, per contract K4.

- New migration `V3__user_subject_and_tool_calls.sql`: nullable `user_subject VARCHAR(255)` and `tool_calls TEXT` on `request_log`. Older rows keep NULL.
- `logRequest` has two new **optional** params, so existing callers keep working:
  - `userSubject`: trimmed; blank means none; more than 255 characters is rejected.
  - `toolCalls`: a JSON array string of at most 10000 characters. Each element must be an object with `tool` and `outcome` (non-blank strings), plus optional `customer` and `resultId` (string or null). Unknown fields are rejected, so only the K4 audit fields are stored (no raw tool arguments). The array is stored as compact JSON. Errors name the element, e.g. `toolCalls[1].outcome is required`.
- `getRequest` now returns `RequestLogDetail`: the existing fields plus `userSubject` and `toolCalls` (the stored JSON array as a string, the same form `logRequest` accepts).
- `listRecentRequests` and the `logRequest` result are unchanged (`RequestLogEntry`). They do **not** include the new fields (K4). The repository's list query doesn't select those columns either.
- Tests: H2 (`RequestLogToolsTests`) covers storing, normalizing and every validation error, and checks over the MCP JSON callbacks that `getRequest` returns both fields and `listRecentRequests` returns neither. PostgreSQL (`PostgresRepositoryTests`) covers the round-trip of the new columns and the V3 migration.
- `./mvnw -pl request-log-mcp-server -am verify` is green (34 tests, coverage and format pass; needs Docker).

**Needs from stream A** (A10)
- Send `userSubject` (JWT `sub`) and `toolCalls` (JSON array string of `{tool, customer, outcome, resultId?}`) to `logRequest`. Any other field in a tool-call object is rejected with an error, so a hash of the arguments would need a K4 extension first.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### Branch `prod/b4-request-log-db-users` (base: `prod/b3-user-subject-tool-calls`)
**PR title:** Request log: separate Flyway (DDL) and app (DML) database users (B4)

**PR body:**
Implements B4 / T2.3 (request-log part) of PRODUCTION_READINESS_PLAN.md.

**Stacked on the B3 branch (`prod/b3-user-subject-tool-calls`).** Merge B3 first. B4's new test calls `logRequest` with the K4 params.

- New `MigrationUserConfig`:
  - When `REQUEST_LOG_DB_MIGRATION_USER` / `REQUEST_LOG_DB_MIGRATION_PASSWORD` are set, Flyway connects as that user (a `FlywayConfigurationCustomizer` on the same JDBC URL). The application pool stays on `REQUEST_LOG_DB_USER`.
  - A Flyway `afterMigrate` callback (runs on every start, so it also covers tables from later migrations) runs as the migration user on PostgreSQL. When the app user differs, it executes `GRANT USAGE ON SCHEMA`, `GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES`, `REVOKE ALL ON flyway_schema_history` and `GRANT USAGE, SELECT ON ALL SEQUENCES`, all with quoted identifiers. DELETE is granted for the retention purge (B5).
  - It does nothing on H2 or when both users are the same, so the `local` profile and the current compose setup are unchanged.
- `prod` profile: `DatabaseUrlGuard` now also fails fast when `REQUEST_LOG_DB_MIGRATION_USER` is unset or equals `REQUEST_LOG_DB_USER`.
- The roles themselves are created once by a DBA or init script (the migration user needs `CREATE` on the schema; the app user needs only `LOGIN`). Neither user needs `CREATEROLE` or superuser.
- Tests: new `LeastPrivilegeTests` start the full app in the **`prod` profile** against PostgreSQL 17 (Testcontainers), with `requestlog_migrator` and `requestlog_app` roles. They check that the app runs as `requestlog_app`, that migrations ran as and are owned by `requestlog_migrator`, that the app user can read, write and delete rows, and that it gets `permission denied` / `must be owner` for `CREATE TABLE`, `DROP`, `ALTER`, `TRUNCATE` and reading `flyway_schema_history`. `DatabaseProfileTests` cover the new prod guard cases and identifier quoting.
- `./mvnw -pl request-log-mcp-server -am verify` is green (41 tests, coverage and format pass; needs Docker).

**Needs from stream C**
- Compose (C8), once the request-log server runs with `SPRING_PROFILES_ACTIVE=prod`: add an init script for the `postgres` service that creates an app role, e.g.
  ```sql
  CREATE ROLE requestlog_app LOGIN PASSWORD '...';   -- from REQUEST_LOG_DB_APP_PASSWORD
  ```
  Then set `REQUEST_LOG_DB_MIGRATION_USER=requestlog` and `REQUEST_LOG_DB_MIGRATION_PASSWORD=${REQUEST_LOG_DB_PASSWORD}` (the existing owner, so existing volumes keep their table ownership), and `REQUEST_LOG_DB_USER=requestlog_app` with its own password. Without the prod profile, compose keeps working unchanged.
- Helm / External Secrets (C11a/b): provide `REQUEST_LOG_DB_MIGRATION_USER` and `REQUEST_LOG_DB_MIGRATION_PASSWORD` (K8) next to the app credentials. For managed Postgres or CloudNativePG (C11c), create both roles; the migration role owns the `requestlog` database/schema.

**README / docs needs (C14/C15)**
- Document the two request-log DB users and the env vars. On PostgreSQL < 15, also run `REVOKE CREATE ON SCHEMA public FROM PUBLIC` so the app user cannot create tables.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
B5 – dependencies satisfied: yes (no ⛓). Retention purge job with ShedLock and the `requestlog_purged_rows_total` metric. Branch from `main` if B3/B4 are merged; otherwise stack on `prod/b4-request-log-db-users`, because ShedLock needs a `shedlock` table migration (V4) and DML grants for it come from B4's callback.

## Blocked on
nothing

## Notes for the next session
- B3 and B4 are stacked (B4 on B3). If B3 changes in review, rebase B4.
- K4 decisions: `getRequest` returns `toolCalls` as a **string** (the same form as the input), not a nested array. Unknown fields in a tool call are rejected.
- B4: `MigrationUserConfig` is a regular `@Configuration`, so `@JdbcTest` slices don't load it (`PostgresRepositoryTests` runs as one user). The grants callback runs on every `migrate`, so a new table in V4+ (e.g. ShedLock's `shedlock`, B5) gets DML grants automatically. Don't grant on it by hand.
- Spring wraps PostgreSQL errors (`bad SQL grammar [...]`); assert with `.rootCause().hasMessageContaining(...)`.
- `@DynamicPropertySource` can set the env-style names (`REQUEST_LOG_DB_URL`, ...) directly; see `LeastPrivilegeTests`.
- The shell `cd && multi-command` heredoc pattern was refused by the worktree guard. Use Write/Edit for new files and keep Bash commands simple.
- Format before verify: `./mvnw -q -B com.spotify.fmt:fmt-maven-plugin:format -pl request-log-mcp-server`.
