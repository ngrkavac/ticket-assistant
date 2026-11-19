# How it works: MCP tools and RAG

Summary of how the two features are wired in this project. Neither is called directly from application code: Spring AI discovers them and plugs them into the `ChatClient`, and they run as part of each `/ask` request.

## MCP tools (`TicketTools`)

The ticket tools (`listCustomers`, `getTicket`, `listOpenTickets`, `listTickets`, `createTicket`) are never called directly (only `TicketToolsTests` does). They are found by reflection and exposed over MCP.

**On the server (`ticket-mcp-server-java`)**
- `TicketServerApplication` defines a `ToolCallbackProvider` bean: `MethodToolCallbackProvider.builder().toolObjects(tools).build()`.
- It scans `TicketTools` for `@Tool` methods. Each becomes a tool definition: name = method name, description = `@Tool(description=...)`, argument JSON schema generated from parameter types and `@ToolParam`.
- The `spring-ai-starter-mcp-server-webmvc` auto-configuration registers all `ToolCallbackProvider` beans with the MCP server, served at `/sse` and `/mcp/message`.

**On the client (`assistant`)**
- At startup `spring-ai-starter-mcp-client` connects to `spring.ai.mcp.client.sse.connections.tickets.url`, sends `initialize`, then `tools/list`.
- It auto-configures a `ToolCallbackProvider` wrapping the discovered tools; `ChatConfig` passes it to `.defaultToolCallbacks(mcpTools)`.

**Per request** ("What is the status of globex ticket T-1003?")
```
POST /ask
  -> ChatClient sends to the LLM: system prompt + question + tool definitions
  -> the model replies with a tool call: getTicket {"customer":"globex","id":"T-1003"}
  -> Spring AI's tool-calling loop finds the matching callback
  -> MCP client sends JSON-RPC "tools/call" over HTTP to the server (port 8081)
  -> server does Method.invoke(ticketTools, "globex", "T-1003")
  -> result serialised to JSON and returned to the model
  -> the model writes the final answer
```
Exceptions thrown by a tool (e.g. `IllegalArgumentException` for a bad id) become an MCP error result that the model can read.

Tickets are stored per customer, and every ticket tool takes the customer name. `getTicket` answers `Ticket <id> not found` when the ticket belongs to a different customer, so one customer's tickets are never visible through another. `createTicket` only accepts known customers and assigns the next global id; created tickets are kept in memory until the server restarts.

**Python and Rust versions.** `ticket-mcp-server-python` and `ticket-mcp-server-rust` serve the same tools on the same port and SSE endpoints, so the client side above does not change. They do not use reflection. The tool definitions (names, descriptions, JSON schemas) are written out by hand to match what Spring AI generates from the `@Tool` annotations. The Python server uses the MCP SDK's low-level `Server` instead of FastMCP, because FastMCP adds an `Error executing tool ...` prefix to error messages. The Rust server implements the SSE transport itself on axum, because the official Rust SDK (`rmcp`) no longer has a server for it. `ticket-mcp-contract-tests` checks that all three behave identically.

**Consequences**
- Descriptions are the API contract: the model only knows what a tool does from `@Tool`/`@ToolParam` text. This matters more with small local models.
- Renaming a method changes the tool name seen by clients.
- Only `@Tool` methods are exposed.
- Debug with `logging.level.org.springframework.ai=DEBUG`.

## RAG (knowledge base)

**Indexing (once, at startup)**
- `RagConfig` defines an `ApplicationRunner` that calls `KnowledgeBaseIndexer.index()`.
- Files matching `helpdesk.kb.location` (default `classpath:kb/*.md`, override with `KB_LOCATION`, e.g. `file:/data/kb/*.md`) are read into `Document`s with a `source` metadata field.
- `TokenTextSplitter` cuts them into chunks; `vectorStore.add(chunks)` embeds each chunk with the local ONNX `EmbeddingModel` and keeps text + vector in an in-memory `SimpleVectorStore`.
- Nothing is persisted: the store is rebuilt on every start. If no files match, readiness stays DOWN.

**Wiring (`ChatConfig`)**
```java
.defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
    .searchRequest(SearchRequest.builder().topK(3).similarityThreshold(0.3).build())
    .build())
```
An advisor is an interceptor around the model call. As a default advisor it runs on every `/ask`.

**Per request** ("My VPN keeps dropping, what should I try?")
```
POST /ask -> AssistantService -> chat.prompt().user(question).call()
  -> QuestionAnswerAdvisor, before the model is called:
       1. embeds the question with the same embedding model
       2. similarity search: top 3 chunks scoring >= 0.3
       3. appends their text to the user message as context
  -> the LLM gets: system prompt + question + retrieved context (+ tool definitions)
```
The model never searches anything; it only sees the extra text pasted into the prompt.

**Consequences**
- `topK` and `similarityThreshold` decide quality. If nothing scores above the threshold, no context is added and the system prompt makes the model say the KB doesn't cover the question.
- Chunking is coarse: short files stay whole, so "top 3" means up to three whole files.
- Sources are not returned in the response (open improvement: expose the retrieved documents' `source`).
- `AssistantIntegrationTests` uses a fake chat model that echoes its prompt to prove retrieved text reaches the prompt.

## How they combine

Both are attached to the same `ChatClient`:

| | RAG | MCP tools |
|---|---|---|
| Triggered by | the advisor, automatically, before the model call | the model, during generation |
| Runs on | every request | only when the model decides to call a tool |
| Data | static markdown knowledge base | live ticket data |

For "What open tickets does acme have, and how do I fix the first one?", the fix instructions come from RAG and the ticket list from the `listOpenTickets` MCP tool.

## Request log (`request-log-mcp-server`)

Every `/ask` request that reaches the model is stored in Postgres through a second MCP server.

- `AssistantService.ask` calls `RequestLogClient.record(...)` in a `finally` block, so both successes and failures are logged.
- `RequestLogClient` calls the `logRequest` MCP tool **directly from code**, on a virtual thread. A slow or failing log server never delays or fails the user's request; failures are only logged as warnings, without the question text.
- The row is keyed by the same id the client receives in the `X-Request-Id` response header (`RequestIdFilter`). `logRequest` is idempotent per request id.
- `ChatConfig.hideRequestLogTools` (an `McpToolFilter`) hides all request-log tools from the LLM. Otherwise a user could ask the model to read other users' questions or forge log entries.
- Requests rejected before `AssistantService` (401 bad API key, 400 validation, 429 too many requests) are **not** logged.

Table `request_log` (Flyway `V1__request_log.sql`, `V2__customer_and_questions.sql`): `id`, `request_id` (unique), `question`, `answer`, `status` (`SUCCESS`/`ERROR`), `error_type`, `duration_ms`, `customer` (nullable), `created_at`.

### Customers and the question list (UI)

- `/ask` takes an optional `customer`. `TicketDirectoryClient` checks it against the ticket server's `listCustomers` tool (called from code, not by the model). `AssistantService` then sends `Customer: <name>` followed by a blank line and the question as the user message. The system prompt tells the model to use that customer for ticket tools and not to ask for it again. The log stores the question as typed, with the customer in its own column.
- `GET /requests?customer=acme&limit=5` filters on that column (`listRecentRequests` with `customer`). The UI shows the last 5 for the selected customer.
- The UI's questions live in table `question_catalog` (seeded with customer-neutral questions in V2) and are read and added through the `listQuestions` and `addQuestion` tools, behind `GET` and `POST /questions` on the assistant. `addQuestion` is idempotent per text.

## Tutorial: send a request and verify it was stored

### The flow

```
curl ──POST /ask──▶ assistant (:8080)
                      │ 1. RAG: QuestionAnswerAdvisor adds matching KB chunks to the prompt
                      │ 2. LLM (Ollama llama3.1) gets the prompt + ticket tool definitions
                      │ 3. model may call a tool ──MCP──▶ ticket-mcp-server-{java|python|rust} (:8081)
                      │                                   ticket tools (getTicket, listTickets, ...)
                      │ 4. model writes the answer ──▶ response + X-Request-Id header
                      │ 5. in the background, app code (not the model) calls
                      └──MCP logRequest──▶ request-log-mcp-server (:8082) ──▶ Postgres request_log
```

### The roles of MCP

| Who calls | Server | Tools | Why |
|---|---|---|---|
| The LLM, when it decides to | `tickets` | `listCustomers`, `getTicket`, `listOpenTickets`, `listTickets`, `createTicket` | Live ticket data the model doesn't have, and creating tickets on request |
| Assistant code, on every request (`RequestLogClient`) | `requestlog` | `logRequest` | Audit trail: question, answer, status, duration |
| A developer through Claude Code (`.mcp.json`) | both | read tools only | Inspect tickets and logs; `logRequest` and `createTicket` are denied in `.claude/settings.json` |

### 1. Start the stack

```bash
docker compose up -d --build
docker compose ps        # all services should be "healthy"
```

`.env` must define `HELPDESK_API_KEY` and `REQUEST_LOG_DB_PASSWORD`, plus `COMPOSE_PROFILES=java` (or `python` / `rust`) to choose the ticket server implementation. The local `docker-compose.override.yml` publishes the MCP servers on `127.0.0.1:8081/8082` so Claude Code can connect. Postgres is never published. The override must use the new service names (`ticket-mcp-server-java`, `-python`, `-rust`); give the `127.0.0.1:8081:8081` port to all three, because only the active one is started.

### 2. Send a request

Run from the project root (it reads `.env`). Keep it on **one line**: when a multi-line command is pasted, trailing spaces after `\` break the line continuation, so curl runs without headers (401) and zsh reports `command not found: -H`.

```bash
KEY=$(grep HELPDESK_API_KEY .env | cut -d= -f2); curl -i -X POST localhost:8080/ask -H 'Content-Type: application/json' -H "X-API-Key: $KEY" -d '{"question":"What open tickets does acme have?"}'
```

Expected (takes 20–60 s on the local model):

```
HTTP/1.1 200
X-Request-Id: 9dfc6a46-436e-470d-876d-c0cbea469930
{"answer":"The open ticket for acme is T-1001, subject \"VPN drops every 10 minutes\", status \"OPEN\", and priority \"HIGH\"."}
```

Other questions to try:

| Question | Exercises |
|---|---|
| `What is the status of globex ticket T-1003?` | MCP `getTicket` |
| `Which customers do we support, and what tickets does initech have?` | MCP `listCustomers` + `listTickets` |
| `Create a HIGH priority ticket for initech: the badge reader at the entrance is broken.` | MCP `createTicket` |
| `My VPN keeps dropping, what should I try?` | RAG only, no tool call |
| `What open tickets does acme have, and how do I fix the first one?` | RAG + MCP `listOpenTickets` |

Note the `X-Request-Id` header: it is the key of the stored row.

### 3. Verify the database

**Option A: SQL.** Postgres isn't published, so run `psql` inside the container:

```bash
# newest entry
docker compose exec postgres psql -U requestlog -d requestlog -x -c "SELECT * FROM request_log ORDER BY created_at DESC, id DESC LIMIT 1;"

# last 5, compact
docker compose exec postgres psql -U requestlog -d requestlog -c "SELECT request_id, status, duration_ms, created_at, left(question,40) FROM request_log ORDER BY created_at DESC LIMIT 5;"

# one request by its X-Request-Id
docker compose exec postgres psql -U requestlog -d requestlog -x -c "SELECT * FROM request_log WHERE request_id='<X-Request-Id>';"
```

Example row:

```
id          | 2
request_id  | 9dfc6a46-436e-470d-876d-c0cbea469930
question    | What open tickets does acme have?
answer      | The open ticket for acme is T-1001, ...
status      | SUCCESS
error_type  |
duration_ms | 23415
created_at  | 2026-11-15 12:30:17.593903+00
```

**Option B: MCP from Claude Code.** With `.mcp.json` enabled, ask Claude "show the last item in the request log" (calls `listRecentRequests`) or "get request `<id>`" (calls `getRequest`).

### Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `/mcp` lists no project servers | `.claude/settings.local.json` has `disabledMcpjsonServers`. Replace it with `enabledMcpjsonServers: ["tickets", "requestlog"]`, or run `claude mcp reset-project-choices`, then restart Claude Code. |
| MCP tool calls from Claude Code time out | The SSE session went stale (for example, containers restarted after Claude Code connected). In `/mcp`, select the server and choose **Reconnect**. Check the server with `curl -N localhost:8082/sse`; it should print an `endpoint` event. |
| `401 Unauthorized` | `X-API-Key` header missing or wrong; usually caused by a broken multi-line paste (see step 2). |
| Request answered but no row in `request_log` | Check `docker compose logs assistant` for `Request ... not stored` warnings. |

## Developer Q&A notes

- **`AssistantService.ask` visibility:** the method is `public`, but the class is package-private, so it is only reachable from other packages if the class is made public too. Least-visibility is the default style; widen it if another package needs the service or an interface is extracted.
- **KB path:** originally hardcoded; now externalised as `helpdesk.kb.location` so docs can be mounted without a rebuild.
- **Alternatives to `ApplicationRunner`:** `CommandLineRunner` (raw args), `@EventListener(ApplicationReadyEvent)`, `@PostConstruct`/`SmartInitializingSingleton` (runs before the web server starts, so no empty-store window but slower startup), persisting `SimpleVectorStore` to a file, or a real vector store (e.g. pgvector) with a separate ingestion job.
