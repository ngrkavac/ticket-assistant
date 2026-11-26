# Stream a handoff – 2026-11-26
## Done this session
- A10 – `prod/a10-request-log-audit` (base `main` 03afa82)
- A16 – `prod/a16-embedding-model-uris` (base `main` 03afa82; independent of A10, no shared files)

## Ready to push
### `prod/a10-request-log-audit` (base: `main`)
**PR title:** Send userSubject and toolCalls to the request log per K4 (A10)

**PR body:**
Stream A, task A10 (T1.5, T1.6, client side). Builds on A9 (merged) and B3 (merged).

- `RequestLogClient.record(..)` takes two more arguments, `userSubject` and `toolCalls`, and sends them as the K4 `logRequest` params. Each is sent only when present: a blank subject or a request without tool calls leaves the param out, so the call looks exactly like before.
- `AssistantService.ask` passes `principal.subject()` (JWT `sub`) and `ToolCallAudit.toJson()`, the K4 JSON array of `{tool, customer, outcome, resultId?}` that A9 collects (null when the model called no tool). A9's caps still apply: at most 30 records, fields at most 64 chars, total at most 10000 chars, which is the server's limit.
- The subject is trimmed and shortened to 255 chars on the client. The server rejects a longer one, and that would lose the whole log entry.
- Nothing beyond K4 is sent or logged. `toolCalls` holds only the four K4 fields (the server rejects unknown fields). A failed write still logs only the request id and the exception class, never the question, the subject or the tool calls. A new test checks this.
- Tests: `RequestLogClientTests` (both params sent; left out when blank; subject shortened; a failed or rejected write logs neither the question nor the subject nor the tool calls) and `AssistantServiceTests` (the request log gets the user's subject and the same `toolCalls` JSON as the audit; null without tool calls). 175 tests, coverage gate met.
- No contract change (K4 is implemented as written).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### `prod/a16-embedding-model-uris` (base: `main`)
**PR title:** Make the embedding model and tokenizer URIs configurable (A16)

**PR body:**
Stream A, task A16 (T3.5, config side). Unblocks C10.

- `application.properties`: `spring.ai.embedding.transformer.onnx.model-uri=${EMBEDDING_MODEL_URI:...}` and `spring.ai.embedding.transformer.tokenizer.uri=${EMBEDDING_TOKENIZER_URI:...}` (K8 names). The defaults are Spring AI's own URIs for all-MiniLM-L6-v2, so nothing changes when the env vars are unset.
- Spring AI reads `file:` and `classpath:` URIs in place and does not copy them into the cache dir (`ResourceCacheService` excludes those schemes). So an image with baked-in files needs no internet access, and its cache dir does not need to be writable for the model.
- Tests: `EmbeddingModelPropertiesTests` binds `spring.ai.embedding.transformer.*` from the real `application.properties`, once with the defaults (equal to `TransformersEmbeddingModel.DEFAULT_ONNX_*`) and once with both env vars set to `file:` paths. 173 tests, coverage gate met.
- Checked by hand (temporary test, not committed): the Spring AI auto-configuration with `file:` URIs pointing at a local copy of `model.onnx` and `tokenizer.json` loaded the model, embedded a sentence (384 dimensions) and wrote nothing to an empty cache dir.
- No contract change.

**Needs from stream C (C10):** bake `model.onnx` and `tokenizer.json` of all-MiniLM-L6-v2 into the assistant image and set `EMBEDDING_MODEL_URI` and `EMBEDDING_TOKENIZER_URI` in the image (or chart/compose) env to their `file:` URIs. Suggested paths: `file:/opt/models/all-MiniLM-L6-v2/model.onnx` and `file:/opt/models/all-MiniLM-L6-v2/tokenizer.json`. Pin the downloads to a fixed commit or checksum, because the default URIs point at Spring AI's `main` branch. README/HOW_IT_WORKS (C15) should list both env vars.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
A11 (T1.8, per-user rate limit with Bucket4j, `RATE_LIMIT_ASKS_PER_MINUTE`) – dependencies satisfied: yes (no ⛓). Then A12, A13. A15 [L] is synced with B10 (S2), so do not start it alone.

## Blocked on
nothing

## Notes for the next session
- A10: `RequestLogClient.record(requestId, question, customer, answer, errorType, durationMs, userSubject, toolCalls)` now has 8 arguments. Any new caller must pass the last two (null is fine).
- A16 touches only `application.properties` and a new test, so A11–A13 can branch from `main`. If A10/A16 are not merged and a task touches `AssistantService`/`RequestLogClient` (A10) or `application.properties` (A16), branch from that task's branch and say so in the PR text.
- Spring AI 1.1.8 transformer property names: `spring.ai.embedding.transformer.onnx.model-uri` and `spring.ai.embedding.transformer.tokenizer.uri`. The model cached locally after a run is in `$TMPDIR/spring-ai-onnx/<uuid>/model.onnx`, which is handy for manual checks.
- The worktree sandbox rejects compound shell commands that mix git with heredocs or several directories. Use plain commands and write Python edit scripts to the scratchpad.
- `./mvnw com.spotify.fmt:fmt-maven-plugin:format -pl assistant` then `./mvnw -pl assistant -am verify`: A10 branch 175 tests, A16 branch 173 tests, both green.
