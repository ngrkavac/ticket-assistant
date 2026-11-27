# ticket-mcp-contract-tests

Black-box MCP tests that every ticket server implementation
(`ticket-mcp-server-java`, `-python`, `-rust`) must pass. They connect like the assistant does and
check tool names, schemas, JSON payloads, error messages, the transport and the readiness endpoint.

```bash
# start any implementation on :8081 first, then
TICKETS_MCP_URL=http://localhost:8081 uv run pytest
```

The transport is chosen with `TICKETS_MCP_TRANSPORT` (contract K6, decision D2):

| Value | Transport | Servers |
|---|---|---|
| `streamable` (default) | Streamable HTTP at `POST /mcp`, stateless (no `Mcp-Session-Id`, JSON responses, `GET /mcp` is 405) | Java (production) and Python |
| `sse` | legacy HTTP+SSE (`GET /sse`, `POST /mcp/message`) | Rust only: a legacy reference server, not deployed and not usable by the assistant (see `ticket-mcp-server-rust/README.md`) |

Each run also checks that the other transport is not served (404), and speaks its own transport
once by hand with redirects disabled, because Spring AI's Java client does not follow redirects.
CI (`contract` job) runs Java and Python with `streamable` and Rust with `sse`.

If the server runs with a service token (`MCP_SERVICE_TOKEN`, contract K5), pass the same
`MCP_SERVICE_TOKEN` to the tests: they send it as `Authorization: Bearer <token>` and also check
that a missing or wrong token gets 401. Without it, that check is skipped. A request with an
`Origin` header gets 403 either way (`MCP_ALLOWED_ORIGINS` is empty by default).

The Java server stores tickets in a database and has the demo data the tests expect only in
its `local` profile: run it with `SPRING_PROFILES_ACTIVE=local` (in-memory H2), and add
`TICKETS_DB_URL`, `TICKETS_DB_USER` and `TICKETS_DB_PASSWORD` to test it on PostgreSQL as CI does.
The tests create tickets, so use a fresh database for each run.
