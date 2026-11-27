# Stream a handoff – 2026-11-27
## Done this session
- A15 [L] – `prod/a15-streamable-http` (base `main` d2f5609)

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

**Needs from stream C:**
- `README.md`/`HOW_IT_WORKS.md` (C15): the assistant uses Streamable HTTP at `/mcp`. MCP Inspector and `curl` examples should use `http://localhost:8081/mcp` and `http://localhost:8082/mcp`, not `/sse`. The assistant starts without MCP servers and reconnects by itself.
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
- `./mvnw com.spotify.fmt:fmt-maven-plugin:format -pl assistant`, then `./mvnw -pl assistant -am verify`: 204 tests, green. A full `./mvnw verify` was green before the stateless test variant was added. That variant touches only assistant tests.
