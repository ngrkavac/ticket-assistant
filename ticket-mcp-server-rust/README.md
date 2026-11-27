# ticket-mcp-server-rust (legacy reference)

A Rust (axum) implementation of the ticket MCP server contract: the same five tools
(`getTicket`, `listOpenTickets`, `listTickets`, `listCustomers`, `createTicket`) on in-memory demo
tickets.

## Status: legacy, SSE only, not for production

Decision D2 in `PRODUCTION_READINESS_PLAN.md`:

- It speaks only the **legacy HTTP+SSE transport** (`GET /sse`, then `POST /mcp/message?sessionId=…`).
  It is **not** migrated to Streamable HTTP.
- The assistant, the Java ticket server (the production one) and the Python reference server use
  **Streamable HTTP at `/mcp`** (contract K6). So the assistant **cannot use this server**, and the
  `rust` compose profile is only for the uses below.
- It is **never deployed**: there is no Helm template for it, and its data is lost on restart.
- It is kept as a reference implementation that the black-box contract suite still checks, so the
  tool contract stays language-neutral.

## What it is still good for

- **Contract tests** (also run in CI, `contract (rust)`):

  ```bash
  docker build -t ticket-mcp-server-rust ticket-mcp-server-rust
  docker run --rm -p 8081:8081 -e MCP_SERVICE_TOKEN=dev-token ticket-mcp-server-rust
  cd ticket-mcp-contract-tests
  TICKETS_MCP_URL=http://localhost:8081 TICKETS_MCP_TRANSPORT=sse MCP_SERVICE_TOKEN=dev-token uv run pytest
  ```

- **MCP Inspector** or any other client that still speaks SSE, e.g. a Claude Code `.mcp.json`
  entry with `"type": "sse"` and `"url": "http://localhost:8081/sse"` (the repository's
  `.mcp.json` uses Streamable HTTP, `"type": "http"`, for the Java and Python servers). A
  browser-based Inspector sends an `Origin` header, so allow it with
  `MCP_ALLOWED_ORIGINS=http://localhost:6274`.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `HOST` | `0.0.0.0` | Listen address |
| `PORT` | `8081` | Listen port |
| `MCP_SERVICE_TOKEN` | empty | Bearer token every MCP request must carry (K5); empty disables the check with a warning |
| `MCP_ALLOWED_ORIGINS` | empty | Comma-separated origins allowed to call; any other `Origin` header gets 403 |

Probes: `/actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness` (no token).

## Build and test

```bash
cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
```
