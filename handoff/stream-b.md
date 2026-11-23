# Stream b handoff – 2026-11-23
## Done this session
- B5 – `prod/b5-retention-purge` (base `origin/main` @ a910a92), plus the C4 follow-up commit (contract job actions pinned by SHA)
- B6 – `prod/b6-db-timeouts` (base `prod/b5-retention-purge`, stacked)

## Ready to push

### Branch `prod/b5-retention-purge` (base: `main`)
**PR title:** Request log: retention purge job with ShedLock and purged-rows metric (B5)

**PR body:**
Implements B5 / T2.4 of PRODUCTION_READINESS_PLAN.md.

- New `RetentionPurgeJob` (wired by `RetentionPurgeConfig`): deletes `request_log` rows older than `request-log.retention` = `REQUEST_LOG_RETENTION` (ISO-8601, default `P90D`, K8). Startup fails if the retention is below `P1D`, which catches typos such as `PT90M`.
  - Runs daily at 03:17 UTC (`request-log.purge.cron`). Rows are deleted oldest first in batches of 1000 (`request-log.purge.batch-size`, max 10000). Each batch is its own short transaction. A run does at most 1000 batches (`request-log.purge.max-batches`); a larger backlog is worked off over the next runs, with a warning in the log.
  - ShedLock (JDBC, `usingDbTime()`): only one replica purges. The lock is held for at most 30 minutes (`lock-at-most-for`) and at least 1 minute (`lock-at-least-for`). New migration `V4__shedlock.sql` creates the `shedlock` table. B4's grants callback gives the app user DML on it automatically.
  - Metric: counter `requestlog.purged.rows`, exported as `requestlog_purged_rows_total` (K9). It is registered at startup, so it exists before the first run. Exposing `/actuator/prometheus` is B11.
  - `request-log.purge.enabled=false` turns the job and scheduling off.
  - The question catalog is not purged, because it holds no per-request data.
- Dependency: ShedLock 6.10.0 (`shedlock-spring`, `shedlock-provider-jdbc-template`). The version is set in the module pom because the root pom is stream C's. 6.x is the line for Spring 6 / Boot 3; 7.x needs Spring 7.
- Tests: `RetentionPurgeTests` (H2) cover the batched purge and the retention boundary, the metric, the lock row, skipping while another replica holds the lock, the max-batches cap, and rejecting unsafe settings. `LeastPrivilegeTests` (PostgreSQL 17, `prod` profile, separate users) check that the **app user** runs the purge and writes its ShedLock row.
- **Separate commit: CI.** The C4 PR asked for this follow-up. In the `contract` job of `.github/workflows/ci.yml`, the actions are now pinned by SHA on their current majors (`actions/checkout` v7.0.1 with `persist-credentials: false`, `docker/setup-buildx-action` v4.4.1, `docker/build-push-action` v7.4.0, `astral-sh/setup-uv` v10.4.0). No other job changed.
- `./mvnw -pl request-log-mcp-server -am verify` is green (48 tests, coverage and format pass; needs Docker).

**Needs from stream C**
- Compose/Helm (C8, C11a): optional `REQUEST_LOG_RETENTION` (default `P90D`) for the request-log server. No other setup is needed: the `shedlock` table comes from Flyway.

**README / docs needs (C14/C15)**
- A data-protection section (T2.4). The request log stores question, answer, status, customer, user subject (JWT `sub`) and tool-call audit records. It can be read through the request-log MCP tools, i.e. by the assistant and by anyone with access to the server or its database. Rows are deleted after `REQUEST_LOG_RETENTION` (default 90 days) by a daily purge (03:17 UTC). The metric is `requestlog_purged_rows_total`. Runbook: a warning "Retention purge stopped after N batches" means a backlog that later runs will work off.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### Branch `prod/b6-db-timeouts` (base: `prod/b5-retention-purge`)
**PR title:** Request log: Hikari and PostgreSQL statement timeouts (B6)

**PR body:**
Implements B6 / T2.6 (request-log part) of PRODUCTION_READINESS_PLAN.md.

**Stacked on the B5 branch (`prod/b5-retention-purge`)**, because both change `request-log-mcp-server/src/main/resources/application.properties` and `LeastPrivilegeTests`. Merge B5 first.

- Hikari: `maximum-pool-size` from `REQUEST_LOG_DB_POOL_SIZE` (default 5, as before), `connection-timeout` 5 s, `validation-timeout` 2 s. When the database is slow or unreachable, a caller now fails after 5 s instead of the default 30 s.
- New `DatabaseTimeoutsConfig`: a `BeanPostProcessor` that gives pooled **PostgreSQL** connections `statement_timeout` from `request-log.db.statement-timeout` = `REQUEST_LOG_DB_STATEMENT_TIMEOUT` (default `5s`; `0` turns it off). It is passed through the driver `options` property (`-c statement_timeout=5000`). It also sets a driver `socketTimeout` 10 s longer, as a backstop for a dead network.
  - This is done in code rather than in the JDBC URL as T2.6 suggested. The URL comes from `REQUEST_LOG_DB_URL`, so the operator would have to remember the option, and H2 (the `local` profile) rejects these driver properties.
  - If `options` / `socketTimeout` are already set, in the URL or in `spring.datasource.hikari.data-source-properties`, they win.
  - Flyway's migration-user connection (required in `prod`, B4) does not go through the pool, so migrations are not cut off.
- Tests: `DatabaseTimeoutsTests` (unit) cover PostgreSQL vs. H2, zero/negative timeouts and explicit settings winning. `LeastPrivilegeTests` (PostgreSQL 17, `prod` profile) check the pool size from the env var, the 5 s connection timeout, `SHOW statement_timeout` = `1s`, and that `pg_sleep(3)` is cancelled by the server.
- `./mvnw -pl request-log-mcp-server -am verify` is green (55 tests, coverage and format pass; needs Docker).

**Needs from stream C**
- Compose/Helm (C8, C11a): optional `REQUEST_LOG_DB_POOL_SIZE` and `REQUEST_LOG_DB_STATEMENT_TIMEOUT` for the request-log server. These are new optional env vars; K8 could list them.

**README / docs needs (C14/C15)**
- Document both env vars. Runbook: "canceling statement due to statement timeout" in the request-log logs means a query ran longer than `REQUEST_LOG_DB_STATEMENT_TIMEOUT`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
B7 [L] – dependencies satisfied: yes for development (⛓ C8 only for compose; use Testcontainers until then). Counts as 2 tasks, so it takes a whole session.

## Blocked on
nothing

## Notes for the next session
- B6 is stacked on B5. If B5 changes in review, rebase B6.
- ShedLock caches which lock rows it has inserted (`StorageBasedLockProvider`), so **deleting** `shedlock` rows in tests breaks later locking. Expire them instead (`UPDATE shedlock SET lock_until = ...`); see `RetentionPurgeTests`.
- Hikari timeouts are milliseconds: Spring Boot does not convert `5s` for them.
- `DatabaseTimeoutsConfig` reads a `Duration` from the `Environment`. In unit tests with `MockEnvironment`, set `new ApplicationConversionService()` so `2s` parses.
- B8 (ticket server least privilege and timeouts) can reuse the B4 `MigrationUserConfig` and B6 `DatabaseTimeoutsConfig` patterns once B7 exists.
- The worktree guard sometimes refuses Bash heredocs that write files. Use Write/Edit for new files.
- Format before verify: `./mvnw -q -B com.spotify.fmt:fmt-maven-plugin:format -pl request-log-mcp-server`.
