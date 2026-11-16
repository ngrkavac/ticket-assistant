# ticket-mcp-contract-tests

Black-box MCP tests that every ticket server implementation
(`ticket-mcp-server-java`, `-python`, `-rust`) must pass. They connect over the
SSE transport like the assistant does and check tool names, schemas, JSON payloads,
error messages and the readiness endpoint.

```bash
# start any implementation on :8081 first, then
TICKETS_MCP_URL=http://localhost:8081 uv run pytest
```
