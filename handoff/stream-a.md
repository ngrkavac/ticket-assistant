# Stream a handoff – 2026-11-23
## Done this session
- A3 – `prod/a3-requestlog-executor` (base `main`)
- A4 – `prod/a4-customers-cache` (base `prod/a3-requestlog-executor`, stacked)

## Ready to push
### `prod/a3-requestlog-executor` (base: `main`)
**PR title:** Bound the request-log write executor and add helpdesk_requestlog_writes_total (A3)

**PR body:**
Stream A, task A3 (T0.6).

- `RequestLogClient` writes `logRequest` on a `ThreadPoolExecutor` with a virtual-thread factory, a bounded `ArrayBlockingQueue` and `AbortPolicy`, instead of an unbounded virtual-thread-per-task executor. When the queue is full (or the app is shutting down), `record()` catches the rejection, drops the write and logs a WARN; `/ask` is never blocked.
- New counter `helpdesk.requestlog.writes{outcome=stored|failed|dropped}`, exported to Prometheus as `helpdesk_requestlog_writes_total{outcome}` (K9).
- New properties: `helpdesk.request-log.write-threads` (env `REQUEST_LOG_WRITE_THREADS`, default 4, max 256) and `helpdesk.request-log.queue-capacity` (env `REQUEST_LOG_QUEUE_CAPACITY`, default 1000, max 100000).
- Tests: `RequestLogClientTests` (overflow increments `dropped` and `record()` returns immediately while the writer is blocked; stored/failed counts; the executor uses virtual threads), `HelpdeskPropertiesTests`.

`./mvnw -pl assistant -am verify` passes.

README needs: document `REQUEST_LOG_WRITE_THREADS`, `REQUEST_LOG_QUEUE_CAPACITY` and the `helpdesk_requestlog_writes_total{outcome}` metric (a rising `dropped` count means the request-log server is too slow or down).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### `prod/a4-customers-cache` (base: `prod/a3-requestlog-executor`)
Stacked on A3 because both change `HelpdeskProperties`, `HelpdeskPropertiesTests`, `RequestLogClientTests` and `application.properties`; branching from `main` would conflict. Merge A3 first, then retarget this PR to `main`.

**PR title:** Cache the customer list from the ticket server with Caffeine (A4)

**PR body:**
Stream A, task A4 (T0.4). Depends on #<A3 PR> (stacked on `prod/a3-requestlog-executor`; retarget to `main` once A3 is merged).

- `TicketDirectoryClient` caches the `listCustomers` result in a Caffeine cache for `helpdesk.customers-cache-ttl` (env `HELPDESK_CUSTOMERS_CACHE_TTL`, default `60s`; `0s` disables the cache; negative values fail at startup). `/customers` and the `/ask` customer check share the cache, so the ticket server gets at most one `listCustomers` call per TTL.
- An unknown customer invalidates the cache and reloads the list once before the request is rejected, so a newly added customer works without waiting for the TTL. Failed loads are never cached.
- Uses `com.github.ben-manes.caffeine:caffeine` directly (version from the Spring Boot BOM) rather than `spring-boot-starter-cache`/`@Cacheable`: there is a single cache entry and `requireKnown` calls `customers()` on the same bean, which a proxy-based `@Cacheable` would not intercept.
- Tests: `TicketDirectoryClientTests` (one MCP call per TTL with a fake ticker, reload on unknown customer, failures not cached, TTL 0), `HelpdeskPropertiesTests`.

`./mvnw -pl assistant -am verify` passes.

README needs: document `HELPDESK_CUSTOMERS_CACHE_TTL`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
A5 – dependencies satisfied: yes

## Blocked on
nothing

## Notes for the next session
- If A3/A4 are not merged when you start A5, branch from `prod/a4-customers-cache` only if A5 touches the same files (`HelpdeskProperties`, `application.properties`, tests constructing `HelpdeskProperties`); otherwise branch from `main`.
- `HelpdeskProperties` now has 10 components (last one `Duration customersCacheTtl`); `RequestLog` has 4 (`enabled, connection, queueCapacity, writeThreads`). Every new component means updating the `new HelpdeskProperties(...)` calls in `AssistantServiceTests`, `KnowledgeBaseIndexerTests`, `RequestLogClientTests` and `HelpdeskPropertiesTests` (a small Python paren-matching script that appends `, null` to every call worked well).
- `RequestLogClient` now needs a `MeterRegistry` (Spring Boot provides one; tests use `SimpleMeterRegistry`). `TicketDirectoryClient` now needs `HelpdeskProperties`; tests use the package-private `(McpSyncClient, Duration, Ticker)` constructor.
- Run `./mvnw com.spotify.fmt:fmt-maven-plugin:format -pl assistant` before `./mvnw -pl assistant -am verify`.
- The worktree sandbox rejects complex compound shell commands that include `git` or `cd ..`; keep git commands simple and run from the worktree root.
