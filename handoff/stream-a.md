# Stream a handoff – 2026-11-27 (A15 E2E fix)
## Done this session
- A15 E2E fix (no new task): commit `acb79bc` on `prod/a15-streamable-http-fix`, a fast-forward of `origin/prod/a15-streamable-http` (890b534). Push it as `prod/a15-streamable-http`: `git push origin prod/a15-streamable-http-fix:prod/a15-streamable-http`.
  - Root cause of the failing smoke (run 45982693909, `POST /ask` 502 after 30 s): qwen2.5:0.5b got stuck in a generation loop, not A15. The `/api/chat` request A15 sends (captured with a logging proxy, A15 assistant + B10 ticket server) is byte-identical to the one main sent before A15 (d2f5609 assistant + SSE ticket server): same system prompt, RAG context, tools, names, schemas, options. Without `createTicket` (acme-agent has no write role) it is 696 prompt tokens, exactly what CI's Ollama logged. Replayed 12 times against qwen2.5:0.5b, 11 answers stopped after 84–188 tokens and 1 ran into the limit: the loop is random (temperature 0.2), so the earlier green runs were luck.
  - Fix: `spring.ai.ollama.chat.options.num-predict=${OLLAMA_NUM_PREDICT:1024}` (`-1` = no limit), test `OllamaChatPropertiesTests`; checked that Ollama receives `num_predict: 1024`. 206 tests, coverage gate met.
- A15 [L] – `prod/a15-streamable-http` (base `main` d2f5609), earlier session

## Ready to push
### `prod/a15-streamable-http` (base: `main`)
**Merge right after B10 (S2).** MCP is broken on `main` between the two merges: before B10 the servers only serve `/sse`, after B10 alone the assistant still asks for `/sse`.

**PR title:** Assistant: MCP over Streamable HTTP, lazy start and reconnect (A15)

**PR body:**
Stream A, task A15 (T3.1 client side + T3.2). Synced with B10 (S2): **merge right after B10**.

- Transport (K6): both connections use `spring.ai.mcp.client.streamable-http.connections.{tickets,requestlog}` with endpoint `/mcp`. `TICKETS_MCP_URL` and `REQUEST_LOG_MCP_URL` stay base URLs (`http://host:8081`, `http://host:8082`), so compose and Helm values need no change. The SSE connections are gone.
- Lazy start: `spring.ai.mcp.client.initialized=false`. The assistant starts with MCP servers down. `/customers`, `/ask` and the history endpoints answer 503 until a server is reachable. The MCP SDK opens the connection on the first call and tries again on every later call. Listing the ticket tools for `/ask` now raises `TicketsUnavailableException` (503) and no longer a generic error.
- Reconnect: new `McpClientSupervisor`. B10's servers are stateless, so a restart only fails the calls made while the server is down, and the next call works. Against a stateful Streamable HTTP server, the SDK reopens a lost session by itself, so the client never has to be recreated. Only the call that hits the lost session fails, and the supervisor repeats it once. That repeat covers reads, `logRequest` (whose request id is unique) and `addQuestion` (idempotent). `createTicket` is never repeated, so a ticket cannot be created twice.
- The model's tools: a new `ChatConfig.mcpToolCallbacks` lists tools only on the ticket connection. It replaces Spring AI's provider (`spring.ai.mcp.client.toolcallback.enabled=false`), which also listed the request-log server's tools, so a request-log server that is down would have failed every `/ask`. `McpToolFilter`, `CustomerScopedToolCallback` and the per-user tool filter (A8/A9) are unchanged.
- K5: the service token goes on every Streamable HTTP request (POST, GET, DELETE). Tests check this against servers that answer 401 without it.
- Tests: `TestMcpServer` is an in-process MCP server (MCP SDK servlet transport on embedded Tomcat) with the K5 token check, in stateless mode (as B10) and in stateful mode. `StatelessMcpReconnectTests` and `StatefulMcpReconnectTests` cover these cases:
  - the app starts with both servers down and connects once they are up;
  - both servers restart, every call still succeeds and the clients are the same objects;
  - a server is down for a while, then comes back;
  - each server gets its own token;
  - `createTicket` runs at most once across a restart;
  - the model gets only ticket tools, which work while the request log is down.
  Unit tests cover `McpClientSupervisor`, the retry rules in `CustomerScopedToolCallback` and `ModelTools`, the new provider, and the `application.properties` binding. 204 tests, coverage gate met.
- Tested against B10's stateless servers: I built `ticket-mcp-server-java` and `request-log-mcp-server` from `prod/b10-streamable-http` (local profile, `MCP_SERVICE_TOKEN` set) and ran the assistant jar from this branch with the matching tokens.
  - It started with no server up, and `/customers` returned 503.
  - After the ticket server started, `/customers` returned 200.
  - With the ticket server stopped it returned 503. After a restart it returned 200 on the first request.
  - `/questions` through the request-log server returned 200.
- No contract change (K5 and K6 as written).
- Generation cap: `spring.ai.ollama.chat.options.num-predict=${OLLAMA_NUM_PREDICT:1024}`. The E2E smoke failed once because qwen2.5:0.5b got stuck in a loop and kept generating until oauth2-proxy's 30 s timeout (502). That was not caused by this PR: the request sent to Ollama is byte-identical to the one from `main` before it (captured and compared), and replaying it shows the tiny model loops in about 1 of 12 calls. With the cap, such a call ends after 1024 tokens with a shorter answer instead of holding `/ask`. `OLLAMA_NUM_PREDICT=-1` turns the cap off.

**Needs from stream C:**
- `README.md`/`HOW_IT_WORKS.md` (C15): the assistant uses Streamable HTTP at `/mcp`. MCP Inspector and `curl` examples should use `http://localhost:8081/mcp` and `http://localhost:8082/mcp`, not `/sse`. The assistant starts without MCP servers and reconnects by itself.
- E2E smoke (`.github/e2e`/workflow): set `OLLAMA_NUM_PREDICT=256` for the assistant, so a looping qwen2.5:0.5b ends after about 5 s (CI generates ~55 tokens/s; 1024 tokens take ~19 s). README/HOW_IT_WORKS should list `OLLAMA_NUM_PREDICT`.
- `docker-compose.yml` (optional): the assistant no longer needs `depends_on: request-log-mcp-server: service_healthy`. `TICKETS_MCP_URL`/`REQUEST_LOG_MCP_URL` stay as they are (base URLs).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
A11 (T1.8, per-user rate limit with Bucket4j, `RATE_LIMIT_ASKS_PER_MINUTE`) – dependencies satisfied: yes (no ⛓). Then A12, A13.

## Blocked on
nothing

## Notes for the next session
- A15 changed `ChatConfig` and `application.properties`. If A15 is not merged yet, A13 (health indicators) should branch from `prod/a15-streamable-http`, and so should any task touching those files.
- A13: there is no MCP readiness indicator yet. Readiness only reflects `readinessState` and `knowledgeBase`, and MCP outages show up as 503s on the API. A cheap probe is `client.listTools()` or `client.ping()` with a short timeout; stateless servers answer `tools/list` without initialize.
- `TestMcpServer` (test sources) gives A12/A13 a real in-process MCP server: `new TestMcpServer(stateless, token, Map.of(tool, responseText)).start()`, plus `restart()`, `stop()`, `authorizations`, `calls(tool)`.
- `AssistantIntegrationTests.Fakes` no longer defines a `ToolCallbackProvider`. `ChatConfig.mcpToolCallbacks` with no MCP clients (`spring.ai.mcp.client.enabled=false`) gives no tools. A test that needs fake tools must override it with `@Primary`.
- The MCP SDK logs `LifecycleInitializer: Handling exception` at WARN with a stack trace whenever a server is unreachable. This is expected noise.
- The worktree sandbox rejects `pkill` and compound git+heredoc commands. Kill your own processes by the PID from `lsof -tiTCP:<port> -sTCP:LISTEN`, and run Python edit scripts from the scratchpad.
- Debugging what the model receives: a tiny Python proxy in front of Ollama that dumps each `/api/chat` body (Spring's client sends chunked bodies), plus the assistant jar with `--spring.profiles.active=local --helpdesk.request-log.enabled=false` and the ticket server jar with `--spring.profiles.active=local` (H2 demo data) on spare ports. No Keycloak needed.
- `./mvnw com.spotify.fmt:fmt-maven-plugin:format -pl assistant`, then `./mvnw -pl assistant -am verify`: 206 tests, green. A full `./mvnw verify` was green before the stateless test variant was added. That variant touches only assistant tests.
