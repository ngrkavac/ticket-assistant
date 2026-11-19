# Stream a handoff – 2026-11-19
## Done this session
- A1 – `prod/a1-catalog-length` (base `main`)
- A2 – `prod/a2-queue-wait-request-id` (base `prod/a1-catalog-length`, stacked)

## Ready to push
### `prod/a1-catalog-length` (base: `main`)
**PR title:** Limit catalog questions separately and map request-log validation errors to 400 (A1)

**PR body:**
Stream A, task A1 (T0.1, assistant side).

- New property `helpdesk.max-catalog-question-length` (default 1000, at most 1000 = the request-log server's limit). `POST /questions` checks it instead of the `/ask` limit (`helpdesk.max-question-length`), so the two limits are independent. A 1001-character catalog question gets 400 with the detail "Question exceeds the maximum length of 1000 characters".
- `RequestLogClient` now tells validation errors from outages: an `isError` result whose text looks like an `IllegalArgumentException` message from the request-log server ("invalid", "is required", "is longer than", "must be") raises the new `RequestLogRejectedException`, mapped to 400 with a generic detail. Other errors stay 503. This also turns an invalid `customer` filter on `/requests` into 400.
- Tests: `QuestionControllerTests`, `RequestLogClientTests`, `HelpdeskPropertiesTests`.

`./mvnw -pl assistant -am verify` passes.

README needs: document `helpdesk.max-catalog-question-length` next to `helpdesk.max-question-length`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### `prod/a2-queue-wait-request-id` (base: `prod/a1-catalog-length`)
Stacked on A1 because both add components to the `HelpdeskProperties` record and touch the same constructor calls in tests; branching from `main` would conflict. Merge A1 first, then retarget this PR to `main`.

**PR title:** Make the queue wait and Retry-After configurable and accept X-Request-Id (A2)

**PR body:**
Stream A, task A2 (T0.5, T0.7). Depends on #<A1 PR> (stacked on `prod/a1-catalog-length`; retarget to `main` once A1 is merged).

- `helpdesk.queue-wait` (env `HELPDESK_QUEUE_WAIT`, default `1s`): how long `/ask` waits for a free slot before 429. `0s` rejects immediately; negative values fail at startup.
- `helpdesk.retry-after` (env `HELPDESK_RETRY_AFTER`, default `5s`): the `Retry-After` header on 429, in whole seconds rounded up; must be positive.
- `RequestIdFilter` keeps an incoming `X-Request-Id` that matches `[A-Za-z0-9._:-]{1,64}` (the same rule as `RequestLogTools.requireRequestId`) and echoes it in the response and logs; anything else is replaced by a random UUID.
- Tests: `AssistantServiceTests`, `AssistantControllerTests`, `HelpdeskPropertiesTests`.

`./mvnw -pl assistant -am verify` passes.

README needs: document `HELPDESK_QUEUE_WAIT`, `HELPDESK_RETRY_AFTER`, and that a valid incoming `X-Request-Id` is propagated (useful for the ingress / nginx `$request_id`). Needs from stream C (optional): nginx could forward `X-Request-Id $request_id` to the assistant.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
A3 – dependencies satisfied: yes

## Blocked on
nothing

## Notes for the next session
- `HelpdeskProperties` is a record; every new component means updating the constructor calls in `AssistantServiceTests`, `KnowledgeBaseIndexerTests`, `RequestLogClientTests` and `HelpdeskPropertiesTests`. If A1/A2 are not merged yet when you start A3, branch from `prod/a2-queue-wait-request-id` (A3 touches `RequestLogClient`, also changed in A1) and say so in the PR text.
- Run `./mvnw com.spotify.fmt:fmt-maven-plugin:format -pl assistant` before `./mvnw -pl assistant -am verify`.
- The worktree sandbox rejects complex compound shell commands that include `git` or `cd ..`; keep git commands simple and run from the worktree root.
- `TooManyRequestsException` now carries its `Duration retryAfter`; the no-arg constructor defaults to 5s.
