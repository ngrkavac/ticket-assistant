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
curl -s localhost:8080/ask -H 'Content-Type: application/json' -d '{"question":"My VPN keeps dropping, what should I try?"}'
# MCP only (live ticket data)
curl -s localhost:8080/ask -H 'Content-Type: application/json' -d '{"question":"What is the status of ticket T-1003?"}'
# Both: RAG + MCP
curl -s localhost:8080/ask -H 'Content-Type: application/json' -d '{"question":"What open tickets does acme have, and how do I fix the first one?"}'
```

Add or edit `.md` files in `kb/` to change the knowledge base (re-indexed on each start).
