# Helpdesk Assistant

Small Spring Boot + Spring AI project to try **MCP** and **RAG** with a practical use case:
an IT helpdesk assistant.

| Module | Role |
|---|---|
| `ticket-mcp-server` (port 8081) | **MCP server** exposing tools `getTicket` and `listOpenTickets` (in-memory demo tickets) over SSE |
| `assistant` (port 8080) | **MCP client** + **RAG**: markdown KB in `assistant/src/main/resources/kb` is embedded locally (ONNX, no API key) into an in-memory `SimpleVectorStore`; a local Ollama model (llama3.1, free) answers via `QuestionAnswerAdvisor` and can call the MCP tools |

Stack: Java 21, Spring Boot 3.5, Spring AI 1.1.8, Maven, Ollama (no paid API).

## Run

```bash
brew install ollama && ollama serve &   # or install the Ollama app
ollama pull llama3.1                      # ~5 GB, supports tool calling
./mvnw -q -pl ticket-mcp-server spring-boot:run      # terminal 1
./mvnw -q -pl assistant spring-boot:run              # terminal 2 (first start downloads the embedding model)
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

Add or edit `.md` files in `kb/` to change the knowledge base (re-indexed on each start). To use files outside the jar, set `KB_LOCATION` (`helpdesk.kb.location`), e.g. `KB_LOCATION='file:/data/kb/*.md'` with a mounted volume; no rebuild needed.

## Production notes

| Area | What is in place |
|---|---|
| API | `POST /ask` with bean validation, configurable max length (`helpdesk.max-question-length`), RFC 7807 errors; upstream failures return a generic 503 (no internals leaked) |
| Auth | Set `HELPDESK_API_KEY` to require an `X-API-Key` header on `/ask` (constant-time compare). The MCP server has no auth: keep it on a private network (compose does not publish its port) |
| Prompt safety | System prompt treats user/ticket text as data; ticket tool inputs are validated |
| Resilience | Virtual threads, max 8 concurrent LLM calls (`helpdesk.max-concurrent-requests`; extra requests get 429 + `Retry-After`), HTTP connect/read timeouts for the LLM, 20s MCP request timeout, graceful shutdown. LLM/MCP failures map to 503, our own bugs to 500. The assistant needs the MCP server reachable at startup (compose enforces ordering) |
| Observability | Actuator on a separate management port (`9090`, not published in compose): `health` (liveness/readiness; readiness includes the KB-indexed check), `metrics`, `prometheus`; `X-Request-Id` header and request id in every log line; question text is never logged |
| Config | Via env: `OLLAMA_URL`, `OLLAMA_MODEL`, `TICKETS_MCP_URL`, `HELPDESK_API_KEY`, `KB_LOCATION`, `MANAGEMENT_PORT`, `EMBEDDING_CACHE_DIR` |
| Packaging | Multi-stage `Dockerfile` (layered jar, non-root user), `docker-compose.yml` (Ollama + both services), GitHub Actions CI (`./mvnw verify`) |
| Embeddings | ONNX model is cached in `EMBEDDING_CACHE_DIR` (`/models` volume in compose), so restarts do not re-download it. The very first start still needs internet |
| Tests | `./mvnw test`: tool validation, controller tests (auth, validation, error mapping) and a full-context integration test with fake models (KB indexing, retrieval, context reaching the prompt); no LLM needed. `./mvnw verify` also enforces 80% line coverage (JaCoCo, `*Application` classes excluded); report in `<module>/target/site/jacoco` |

Run everything in containers:

```bash
HELPDESK_API_KEY=change-me docker compose up --build
curl -s localhost:8080/ask -H 'X-API-Key: change-me' -H 'Content-Type: application/json' -d '{"question":"How do I reset my password?"}'
```

Known limits (deliberate for a demo): in-memory vector store re-indexed on each start, demo tickets in memory, no rate limiting or TLS (put a gateway/ingress in front), Docker setup not built or run here.
