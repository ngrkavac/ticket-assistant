# Helpdesk Assistant

Small Spring Boot + Spring AI project to try **MCP** and **RAG** with a practical use case:
an IT helpdesk assistant.

| Module | Role |
|---|---|
| `ticket-mcp-server` (port 8081) | **MCP server** exposing tools `getTicket` and `listOpenTickets` (in-memory demo tickets) over SSE |
| `request-log-mcp-server` (port 8082) | **MCP server** backed by a database (PostgreSQL in compose, in-memory H2 by default) that stores every `/ask` request. Tools: `logRequest`, `getRequest`, `listRecentRequests` |
| `assistant` (port 8080) | **MCP client** + **RAG**: markdown KB in `assistant/src/main/resources/kb` is embedded locally (ONNX, no API key) into an in-memory `SimpleVectorStore`; a local Ollama model (llama3.1, free) answers via `QuestionAnswerAdvisor` and can call the MCP tools |

Stack: Java 21, Spring Boot 3.5, Spring AI 1.1.8, Maven, Ollama (no paid API).

## Run

```bash
brew install ollama && ollama serve &   # or install the Ollama app
ollama pull llama3.1                      # ~5 GB, supports tool calling
./mvnw -q -pl ticket-mcp-server spring-boot:run      # terminal 1
./mvnw -q -pl request-log-mcp-server spring-boot:run # terminal 2 (in-memory H2 unless REQUEST_LOG_DB_URL is set)
./mvnw -q -pl assistant spring-boot:run              # terminal 3 (first start downloads the embedding model)
```

## Try it

```bash
# RAG only (answer comes from KB)
curl -s localhost:8080/ask -H "X-API-Key: $HELPDESK_API_KEY" -H 'Content-Type: application/json' -d '{"question":"My VPN keeps dropping, what should I try?"}'
# MCP only (live ticket data)
curl -s localhost:8080/ask -H "X-API-Key: $HELPDESK_API_KEY" -H 'Content-Type: application/json' -d '{"question":"What is the status of ticket T-1003?"}'
# Both: RAG + MCP
curl -s localhost:8080/ask -H "X-API-Key: $HELPDESK_API_KEY" -H 'Content-Type: application/json' -d '{"question":"What open tickets does acme have, and how do I fix the first one?"}'
```

## Web UI (`ui/`)

Minimal React + TypeScript (Vite) page, built from the Figma frame in `FIGMA_IMPLEMENTATION_PLAN.md`: pick a predefined question, press **Ask**, and see the questions stored in the request log (newest first).

```bash
cd ui && npm install
HELPDESK_API_KEY=... npm run dev     # http://localhost:5173 (or put the key in ui/.env.local)
```

The Vite dev server proxies `/ask` and `/requests` to the assistant (`ASSISTANT_URL`, default `http://localhost:8080`) and adds the `X-API-Key` header itself, so the key never reaches the browser. This is a dev-only setup; `npm run build` produces static files in `ui/dist` but serving them in production (and injecting the key) is not set up.

`GET /requests?limit=20` (1-100, same API key as `/ask`) returns `requestId`, `question`, `status`, `createdAt` of the newest requests by calling the request-log server's `listRecentRequests` tool. Answers and error details are not returned. 503 if the log server is unavailable or logging is disabled.

## Request log

After every `/ask` that reaches the model, the assistant calls the `logRequest` tool on `request-log-mcp-server` (MCP connection `requestlog`) with the request id (same as the `X-Request-Id` header), question, answer, status (`SUCCESS`/`ERROR`), error class and duration. The call runs in the background: if the log server is slow or down, the user still gets the answer and the assistant logs a warning (without the question text). Requests rejected before the model call (401, 400, 429) are not stored. `logRequest` is idempotent per request id.

The request-log tools are filtered out of the model's tool list (`McpToolFilter` in `ChatConfig`), so users cannot read other users' questions or write fake entries through the chat. Read the log from any MCP client (e.g. MCP Inspector against `http://localhost:8082/sse`) with `getRequest` / `listRecentRequests`, or query the `request_log` table directly. The schema is managed by Flyway (`request-log-mcp-server/src/main/resources/db/migration`).

Config: `REQUEST_LOG_MCP_URL` (assistant, default `http://localhost:8082`), `REQUEST_LOG_ENABLED=false` to turn logging off (the connection is still opened at startup; remove `spring.ai.mcp.client.sse.connections.requestlog.url` to drop it entirely), and on the server `REQUEST_LOG_DB_URL`, `REQUEST_LOG_DB_USER`, `REQUEST_LOG_DB_PASSWORD`.

Add or edit `.md` files in `kb/` to change the knowledge base (re-indexed on each start). To use files outside the jar, set `KB_LOCATION` (`helpdesk.kb.location`), e.g. `KB_LOCATION='file:/data/kb/*.md'` with a mounted volume; no rebuild needed.

## Production notes

| Area | What is in place |
|---|---|
| API | `POST /ask` with bean validation, configurable max length (`helpdesk.max-question-length`), RFC 7807 errors; upstream failures return a generic 503 (no internals leaked) |
| Auth | Set `HELPDESK_API_KEY` to require an `X-API-Key` header on `/ask` (constant-time compare). The MCP servers have no auth: keep them on a private network (compose publishes neither them nor PostgreSQL) |
| Prompt safety | System prompt treats user/ticket text as data; ticket tool inputs are validated |
| Resilience | Virtual threads, max 8 concurrent LLM calls (`helpdesk.max-concurrent-requests`; extra requests get 429 + `Retry-After`), HTTP connect/read timeouts for the LLM, 20s MCP request timeout, graceful shutdown. LLM/MCP failures map to 503, our own bugs to 500. The assistant needs the MCP server reachable at startup (compose enforces ordering) |
| Observability | Actuator on a separate management port (`9090`, not published in compose): `health` (liveness/readiness; readiness includes the KB-indexed check), `metrics`, `prometheus`; `X-Request-Id` header and request id in every log line; question text is never written to application logs (it is stored in the request-log database, see above) |
| Config | Via env: `OLLAMA_URL`, `OLLAMA_MODEL`, `TICKETS_MCP_URL`, `REQUEST_LOG_MCP_URL`, `REQUEST_LOG_ENABLED`, `HELPDESK_API_KEY`, `KB_LOCATION`, `MANAGEMENT_PORT`, `EMBEDDING_CACHE_DIR`; request-log server: `REQUEST_LOG_DB_URL`, `REQUEST_LOG_DB_USER`, `REQUEST_LOG_DB_PASSWORD` |
| Packaging | Multi-stage `Dockerfile` (layered jar, non-root user), `docker-compose.yml` (Ollama, PostgreSQL + all three services), GitHub Actions CI (`./mvnw verify`) |
| Embeddings | ONNX model is cached in `EMBEDDING_CACHE_DIR` (`/models` volume in compose), so restarts do not re-download it. The very first start still needs internet |
| Tests | `./mvnw test`: tool validation, controller tests (auth, validation, error mapping) and a full-context integration test with fake models (KB indexing, retrieval, context reaching the prompt); no LLM needed. `./mvnw verify` also enforces 80% line coverage (JaCoCo, `*Application` classes excluded); report in `<module>/target/site/jacoco` |

Run everything in containers:

```bash
HELPDESK_API_KEY=change-me REQUEST_LOG_DB_PASSWORD=change-me-too docker compose up --build
curl -s localhost:8080/ask -H 'X-API-Key: change-me' -H 'Content-Type: application/json' -d '{"question":"How do I reset my password?"}'
```

Known limits (deliberate for a demo): in-memory vector store re-indexed on each start, demo tickets in memory, no retention/purge for the request log (questions are kept indefinitely: add a cleanup job if they may contain personal data), no rate limiting or TLS (put a gateway/ingress in front), Docker setup not built or run here.
