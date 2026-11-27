# Stream a handoff – 2026-11-27
## Done this session
- A11 – `prod/a11-rate-limit` (base `main` e2a22b6), commit `ad596f4`
- A12 – `prod/a12-authz-matrix` (base `prod/a11-rate-limit`), commit `1937319` (+ this handoff commit)

## Ready to push
### `prod/a11-rate-limit` (base: `main`)
**PR title:** Assistant: per-user rate limit on /ask with Bucket4j (A11)

**PR body:**
Stream A, task A11 (T1.8).

- New `AskRateLimiter`: each user (JWT `sub`) may ask `helpdesk.rate-limit.asks-per-minute` questions per minute (env `RATE_LIMIT_ASKS_PER_MINUTE`, K8; default 20; `0` turns the limit off). Bucket4j greedy refill, so a user can ask a burst of 20 and then one every 3 s.
- Further asks get **429** with `Retry-After` = seconds until the next ask is allowed (rounded up), and the detail "You have asked too many questions in a short time. Please wait a moment." (different from the "assistant is busy" 429 of the global semaphore). Rejections are counted in `helpdesk_ask_rejected_total{reason="rate_limit"}` (K9).
- Checked in `AssistantController` after body validation and before the customer checks: an ask denied for its customer (403) also counts, so the limit also slows down probing. Invalid bodies (400) and requests without a valid token (401/403 by role) do not count.
- Buckets are in memory per pod (accepted by T1.8): with N replicas a user gets up to N × the limit. They live in a Caffeine cache (expire after 1 min idle, when they are full anyway; at most 100 000 users).
- Config: new `HelpdeskProperties.RateLimit` record (`helpdesk.rate-limit.asks-per-minute`, 0–100 000).
- Dependency: `com.bucket4j:bucket4j_jdk17-core:8.22.0` in `assistant/pom.xml` (not in the Spring Boot BOM; the root pom is stream C's, so the version is in the module pom).
- Tests: `AskRateLimiterTests` (fake clock: limit, Retry-After, per-user buckets, idle reset, 0 = off, metric), `RateLimitPropertiesTests` (env binding and default), new `AssistantControllerTests` cases (429 + Retry-After + no downstream call, 403 asks count, 400 asks do not). 217 tests, coverage gate met.

**README needs:**
- `RATE_LIMIT_ASKS_PER_MINUTE` (default 20, 0 = off): per-user limit on `/ask`, per pod. Over the limit: 429 with `Retry-After`.

**Needs from stream C:**
- Compose/Helm (optional): pass `RATE_LIMIT_ASKS_PER_MINUTE` to the assistant if a non-default is wanted. The E2E smoke and k6 (B13) should stay under 20 asks per user per minute, or set it higher (B13 load test from one user will hit it: use several users or raise the limit for the run).
- UI: a 429 from `/ask` now has two causes (busy or per-user limit); the `detail` text is user-readable and can be shown as is.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### `prod/a12-authz-matrix` (base: `prod/a11-rate-limit`)
**Branched from `prod/a11-rate-limit` (A11), merge after it.** The matrix test mocks A11's `AskRateLimiter`, which only exists on that branch. Once A11 is merged, GitHub retargets this PR to `main`.

**PR title:** Assistant: auth/authz test matrix and cross-customer integration test (A12)

**PR body:**
Stream A, task A12 (T1.9). Tests only, no production code change. Based on the A11 PR (`prod/a11-rate-limit`), merge after it.

- `AuthorizationMatrixTests` (`@WebMvcTest` of all four controllers with the real `SecurityConfig`): every endpoint (`POST /ask` for acme/globex, `GET /customers`, `GET /requests` for acme/globex/all, `GET /questions`, `POST /questions`) × every kind of user (no token, rejected token, no role, `tickets-write` only, acme agent, acme writer, globex agent, admin without agent role, admin) as a readable status table (72 cases). Requests carry real `Authorization: Bearer` headers; only the `JwtDecoder` is mocked, so the K1 claim mapping (`HelpdeskJwtConverter`) runs as in production (the `jwt()` post-processor would skip it). Every denied case is checked to reach neither the model, the ticket server nor the request log. Also: `/customers` only lists the token's customers, customer ids are case-insensitive, and a customer outside the token is 403 whether or not it exists (also `*`).
- `CrossCustomerIntegrationTests` (`@SpringBootTest`): a fake model makes scripted tool calls through Spring AI's real `ToolCallingManager`, with in-memory ticket tools in place of the MCP server. Covers: the model asking for another customer's ticket is refused before the ticket server, audited (K4 `toolCalls` sent to the request log: `denied`) and counted (`helpdesk_tool_denied_total`); a call without customer is scoped to the request's customer; `" ACME "` is normalized; mixed calls in one turn (only the other customer's are refused, also `*`); an admin is still scoped to the request's customer; `createTicket` is offered only to writers and still scoped; 12 concurrent asks for acme and globex each keep their own customer. Checked that 4 of these tests fail when the customer check in `CustomerScopedToolCallback` is removed.
- A guard test fails when an endpoint is added without adding it to the matrix.
- `TestUsers.user(subject, …)` overload for users with distinct subjects.
- 308 tests, coverage gate met.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
A13 (T3.3, health indicators `ticketsMcp`, `requestLogMcp`, `ollama`; readiness group) – dependencies satisfied: yes (A15 is merged). Then A17, A18.

## Blocked on
nothing

## Notes for the next session
- A11 added a component to `HelpdeskProperties` (`rateLimit`, last). Tests constructing it pass one more `null`. If A11 is not merged yet when a task touches `HelpdeskProperties`, `AssistantController` or `application.properties`, branch from `prod/a12-authz-matrix` (it contains A11).
- `AssistantController` now needs an `AskRateLimiter`: `@WebMvcTest`s of it must `@MockitoBean AskRateLimiter`. `@SpringBootTest`s that ask more than 20 times per subject a minute must raise `helpdesk.rate-limit.asks-per-minute` (all `TestUsers` users share subject `user-1`; use the new `TestUsers.user(subject, …)`).
- K9: A11 registers `helpdesk_ask_rejected_total{reason="rate_limit"}`. A17 should add the other reasons to the same counter name (`AskRateLimiter.REJECTED_METRIC`).
- A fake model that really runs tools: `CrossCustomerIntegrationTests.ScriptedModel` (overrides `getDefaultOptions()` with `ToolCallingChatOptions`, otherwise ChatClient does not pass tool callbacks/context to the model) plus a `@Primary ToolCallbackProvider` in place of the MCP tools.
- The worktree sandbox rejects multi-step `python3 - <<EOF` scripts that write several files and `sed -i` chained with heredocs; use the Edit tool or single-purpose commands.
- `./mvnw com.spotify.fmt:fmt-maven-plugin:format -pl assistant`, then `./mvnw -pl assistant -am verify`: 308 tests, green.
