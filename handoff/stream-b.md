# Stream b handoff – 2026-11-19
## Done this session
- B1 – `prod/b1-request-log-profiles` (base `main` @ dfa23ce)
- B2 – `prod/b2-request-log-testcontainers` (base `main` @ dfa23ce)

## Ready to push

### Branch `prod/b1-request-log-profiles` (base: `main`)
**PR title:** Request log: remove the H2 default, add `local` and `prod` profiles (B1)

**PR body:**
Implements B1 / T2.2 of PRODUCTION_READINESS_PLAN.md.

- `application.properties` no longer defaults to in-memory H2. `REQUEST_LOG_DB_URL`, `REQUEST_LOG_DB_USER` and `REQUEST_LOG_DB_PASSWORD` have no defaults.
- New `DatabaseUrlGuard` (a `BeanFactoryPostProcessor`, active outside `local`) fails startup with a clear message before the DataSource or Flyway are created:
  - URL missing: `REQUEST_LOG_DB_URL must be set (a jdbc:postgresql: URL). For an in-memory H2 database on a developer machine, run with the 'local' profile.`
  - `prod` profile and a non-PostgreSQL URL: rejected. Only the scheme is echoed, so credentials never reach the logs.
- New `application-local.properties`: H2 in PostgreSQL mode (still overridable with `REQUEST_LOG_DB_URL`).
- New `application-prod.properties`: PostgreSQL only, with no defaults.
- Tests: `RequestLogToolsTests` runs with `@ActiveProfiles("local")`. New `DatabaseProfileTests` cover the default and `prod` profiles failing without a URL, `prod` rejecting H2, `local` starting on H2, and the guard's unit cases.
- `./mvnw -pl request-log-mcp-server -am verify` is green (18 tests, coverage gate and format check pass).

**Needs from stream C**
- C8 (compose): set `SPRING_PROFILES_ACTIVE=prod` on `request-log-mcp-server`. Compose already sets `REQUEST_LOG_DB_URL`, so it keeps working without this.
- Helm (C11a): set `SPRING_PROFILES_ACTIVE=prod` for the request-log server.

**README needs (C15)**
- Local run: `./mvnw -q -pl request-log-mcp-server spring-boot:run -Dspring-boot.run.profiles=local` (in-memory H2). Without the `local` profile, `REQUEST_LOG_DB_URL` is required.
- Remove "in-memory H2 by default" from the module table and the config notes.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### Branch `prod/b2-request-log-testcontainers` (base: `main`)
**PR title:** Request log: Testcontainers PostgreSQL repository tests, fix idempotent insert on PostgreSQL (B2)

**PR body:**
Implements B2 / T2.5 of PRODUCTION_READINESS_PLAN.md.

- New `PostgresRepositoryTests`: `@JdbcTest` against `postgres:17-alpine` (Testcontainers with `@ServiceConnection`, the same image as compose) and the real Flyway migrations. Test-managed transactions are off, so each repository call commits as it does in production. It covers the column round-trip including `timestamptz`, identity ids, `LIMIT` binding, newest-first ordering with the `id DESC` tie-break for equal timestamps, the customer filter, the seeded catalog, and idempotent saves.
- **Bug fix found by these tests:** on PostgreSQL, a retried `logRequest` with an existing request id threw `current transaction is aborted`. `save` caught `DuplicateKeyException` and then read back inside the same `@Transactional` method, but PostgreSQL had already aborted the transaction. H2 hid this. `RequestLogRepository.save` and `QuestionRepository.save` now use `INSERT ... ON CONFLICT DO NOTHING`. There is no conflict target, so the same SQL also runs on H2.
- Test dependencies: `spring-boot-testcontainers`, `org.testcontainers:junit-jupiter` and `org.testcontainers:postgresql`, with versions from the Boot BOM. **`./mvnw verify` now needs Docker.** GitHub's `ubuntu-latest` runners have it.
- `./mvnw -pl request-log-mcp-server -am verify` is green locally with Docker Desktop (22 tests, coverage and format pass). A trial merge with B1 is also green (28 tests).

**Merge note:** B1 and B2 tick adjacent checkbox lines in `PRODUCTION_READINESS_PLAN.md`, so the second merge has a trivial conflict there. Keep both `[x]`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
B3 – dependencies satisfied: yes (no ⛓). Migration `V3__user_subject_and_tool_calls.sql` plus the `logRequest`/`getRequest` changes per K4. Branch from `main`. If B1/B2 are not merged yet, note that `RequestLogRepository.save` changes in B2 (ON CONFLICT), so expect a small conflict or branch from B2 and say so in the PR.

## Blocked on
nothing

## Notes for the next session
- Docker Desktop is available locally, and Testcontainers works (postgres:17-alpine pulls fine).
- H2 does **not** accept `ON CONFLICT (col) DO NOTHING` with a target, even in PostgreSQL mode. Use the targetless `ON CONFLICT DO NOTHING`.
- `@JdbcTest` slices still load the `@Bean` in `RequestLogServerApplication` (`requestLogToolCallbacks`), so slice tests must `@Import(RequestLogTools.class)`. Alternatively, a later task could move that bean into its own `@Configuration`.
- When adding columns (B3), extend `PostgresRepositoryTests` too, and keep the `RequestLogToolsTests` (H2, `local` profile) passing.
- Format before verify: `./mvnw -q -B com.spotify.fmt:fmt-maven-plugin:format -pl request-log-mcp-server`.
