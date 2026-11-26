# Stream a handoff – 2026-11-25
## Done this session
- A9 – `prod/a9-write-gating-tool-audit` (base `main` 5986b1e)
- A14 – `prod/a14-mcp-service-token` (base `main` 5986b1e; independent of A9, no shared files)

## Ready to push
### `prod/a9-write-gating-tool-audit` (base: `main`)
**PR title:** Offer createTicket only with tickets-write and collect tool-call audit records (A9)

**PR body:**
Stream A, task A9 (T1.5, assistant side: write gating and audit collection).

- The ticket tools are no longer `ChatClient` default tools. The new `ModelTools` bean wraps the MCP tools in `CustomerScopedToolCallback` (as before: tools without a `customer` argument are dropped) and `AssistantService` passes `modelTools.offeredTo(principal)` per request.
- Write tools (`CustomerScopedToolCallback.WRITE_TOOLS` = `createTicket`) are offered only to principals with `tickets-write`. Defence in depth: if one is called anyway without the role, the callback refuses it with the tool error `Not allowed for this user`, a WARN log and `helpdesk_tool_denied_total{tool}`; the ticket server is not called.
- New `ToolCallAudit`, put into the tool context per request (key `toolCallAudit`). `CustomerScopedToolCallback` adds a record for every call: `{tool, customer, outcome (success|error|denied), resultId?}`, where `resultId` is the created ticket's id for write tools. `toJson()` gives the K4 `toolCalls` JSON (at most 30 records, fields at most 64 chars, total at most 10000 chars). Sending it with `userSubject` to `logRequest` is A10; for now every write-tool call is logged at INFO with the user's subject, customer, outcome and ticket id.
- System prompt: if there is no tool to create tickets, the model says the user is not allowed to create tickets.
- Tests: `ModelToolsTests`, `ToolCallAuditTests`, new cases in `CustomerScopedToolCallbackTests` (write gating, audit of success, error and denied calls, `resultId` parsing) and `AssistantServiceTests` (offered tools per role, the audit of a request with tool calls, one audit per request). 159 tests, coverage gate met.
- No contract change.

**Needs from stream C (docs):** `README.md`/`HOW_IT_WORKS.md` should say that `createTicket` is only available to users with the `tickets-write` role (e.g. `acme-writer`); other users get an answer that they may not create tickets.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### `prod/a14-mcp-service-token` (base: `main`)
**PR title:** Send the MCP service tokens per K5 (A14)

**PR body:**
Stream A, task A14 (T3.4, client side).

- New `McpServiceTokens`, the `McpSyncHttpClientRequestCustomizer` bean that Spring AI's MCP client auto-configuration applies to every SSE (and, after A15, Streamable HTTP) transport. Every request to a connection's server (the SSE GET and every message POST) carries `Authorization: Bearer <token>`.
- Tokens per connection: `helpdesk.mcp.tokens.tickets=${TICKETS_MCP_TOKEN:}` and `helpdesk.mcp.tokens.requestlog=${REQUEST_LOG_MCP_TOKEN:}` (K5 names; compose from C8 already sets both). A request gets a token only if its scheme, host and port match that connection's URL, so a token is never sent anywhere else.
- **Harmless before B9:** a blank token sends no header, and servers without a token ignore the header. Startup fails on a token with spaces or control characters, or when two connections share an origin but have different tokens. Startup logs which connections send a token (never the token itself).
- Tests: `McpServiceTokensTests` (matching, other origins, blank tokens, default ports, validation, Spring context binding for SSE and Streamable HTTP connections) and `McpServiceTokenTransportTests`: the real `HttpClientSseClientTransport` against a stub MCP server that answers 401 without the bearer token, like B9's `McpServiceAuthConfig`. With the token, initialize and `tools/list` work and every request carried it. Without it, the connection is refused. 155 tests, coverage gate met.
- **Verified against B9:** I built the ticket server and request-log server jars from `prod/b9-mcp-service-token` (a `git archive` copy, no stream B files edited) and ran them in the `local` profile with `MCP_SERVICE_TOKEN` set (curl without a token: 401). The assistant from this branch, started with `TICKETS_MCP_TOKEN`/`REQUEST_LOG_MCP_TOKEN`, started normally (both MCP connections initialized). `GET /customers` (ticket server) and `GET /questions` (request-log server) returned 200 with data.
- **B9 can be merged right after this PR.**
- No contract change (K5 is implemented as written).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
A10 (T1.5, T1.6 client side: send `userSubject` and `toolCalls` per K4) – dependencies satisfied: yes (B3 merged). It needs A9. If A9 is not merged yet, branch A10 from `prod/a9-write-gating-tool-audit` and say so in the PR text.

## Blocked on
nothing

## Notes for the next session
- A10: in `AssistantService.ask` the `ToolCallAudit audit` is a local variable. Pass `principal.subject()` and `audit.toJson()` (null when there were no tool calls) to `RequestLogClient.record(..)` and add `userSubject`/`toolCalls` to the `logRequest` args (K4: subject at most 255 chars). The request-log server rejects unknown fields in `toolCalls` elements, and `ToolCallAudit.ToolCall` uses only the K4 ones.
- Tools are now chosen per request: `ModelTools.offeredTo(principal)` → `chat.prompt().toolCallbacks(..)`. The `ChatClient` bean has no default tools. Tool context keys: `CustomerScopedToolCallback.CUSTOMER`, `PRINCIPAL`, `AUDIT`.
- MCP service tokens: `McpServiceTokens` is the one `McpSyncHttpClientRequestCustomizer` bean. The auto-config uses it only if it is unique (`ifUnique`), so do not add a second one in A15. Merge the logic into it instead. It already reads Streamable HTTP connection URLs.
- The worktree sandbox rejects compound shell commands with git and heredocs. Use plain commands and write Python edit scripts to the scratchpad. A broad `pkill -f` is refused, so stop background servers with TaskStop.
- `./mvnw com.spotify.fmt:fmt-maven-plugin:format -pl assistant` then `./mvnw -pl assistant -am verify`: A9 branch 159 tests, A14 branch 155 tests, both green.
