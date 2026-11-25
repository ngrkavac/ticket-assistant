# ticket-mcp-contract-tests

Black-box MCP tests that every ticket server implementation
(`ticket-mcp-server-java`, `-python`, `-rust`) must pass. They connect over the
SSE transport like the assistant does and check tool names, schemas, JSON payloads,
error messages and the readiness endpoint.

```bash
# start any implementation on :8081 first, then
TICKETS_MCP_URL=http://localhost:8081 uv run pytest
```

The Java server stores tickets in a database and has the demo data the tests expect only in
its `local` profile: run it with `SPRING_PROFILES_ACTIVE=local` (in-memory H2), and add
`TICKETS_DB_URL`, `TICKETS_DB_USER` and `TICKETS_DB_PASSWORD` to test it on PostgreSQL as CI does.
The tests create tickets, so use a fresh database for each run.
