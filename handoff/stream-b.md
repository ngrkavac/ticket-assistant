# Stream b handoff – 2026-11-27
## Done this session
- B10 [L] – `prod/b10-streamable-http` (base `main` at d2f5609)

## Ready to push
### Branch `prod/b10-streamable-http` (base: `main`)
**Merge order (S2):** merge this PR first (contract suite green), then stream A's A15 (assistant on Streamable HTTP) **immediately after**. Between the two merges MCP is broken on `main`: the assistant still connects to `/sse`, which this PR removes.

**PR title:** MCP servers: Streamable HTTP at /mcp, SSE removed (B10)

**PR body:**
Implements B10 / T3.1 (server side) of PRODUCTION_READINESS_PLAN.md, contract K6 and decision D2.

**Merge order (sync point S2): merge this PR first, then A15 (assistant client on Streamable HTTP) right after.** Until A15 is merged the assistant cannot reach the MCP servers (it still uses `/sse`).

- Java ticket server (8081) and request-log server (8082): `spring.ai.mcp.server.protocol=STATELESS`, endpoint `/mcp` (`spring.ai.mcp.server.streamable-http.mcp-endpoint`). `GET /sse` and `POST /mcp/message` are gone (404).
- **Decision to review: the stateless mode of Streamable HTTP.** No `Mcp-Session-Id`; every `POST /mcp` is answered on its own with one JSON response; `GET /mcp` is 405 (no server-to-client stream). Reason: with sessions in memory, a second replica behind a Kubernetes Service (HPA/PDB in C11a) would answer requests of a session it does not know with 404. The servers never call the client (no sampling, no notifications), so they need no session. Spring AI's / the MCP Java SDK's Streamable HTTP client works with it (initialize, then plain POSTs; tested with `McpClient.sync(HttpClientStreamableHttpTransport…)`).
- Python ticket server: the SDK's `StreamableHTTPSessionManager(stateless=True, json_response=True)` on an exact `POST /mcp` route (no `/mcp` → `/mcp/` redirect, which the Java client would not follow), started in the Starlette lifespan. Same behaviour as Java.
- Rust ticket server: unchanged, stays on SSE (D2). New `ticket-mcp-server-rust/README.md`: legacy SSE reference, not deployed, not usable by the assistant; only for the contract tests (`TICKETS_MCP_TRANSPORT=sse`) and MCP Inspector / SSE clients.
- Service token and Origin check (K5) cover `/mcp`: the filters guard every path except `/actuator/**`. Tests: `McpServiceAuthTests` (Java, both servers) and `test_auth.py` (Python) now hit `/mcp`; new `StreamableHttpTransportTests` (Java, both servers: a real MCP Java client initializes, lists and calls tools with the token, a wrong token cannot initialize; raw HTTP: no session id, `tools/list` without initialize, `GET /mcp` 405) and `test_transport.py` (Python); `/sse` and `/mcp/message` are 404.
- Contract tests: transport-selectable with `TICKETS_MCP_TRANSPORT=streamable|sse` (default `streamable`; anything else is a usage error). Streamable uses the SDK's `streamable_http_client` (mcp pinned to `>=1.30`). New `test_raw_streamable_http_without_redirects` (stateless behaviour, no redirects) and `test_the_other_transport_is_not_served`; the raw SSE test runs only with `sse`; the Origin/token checks post to the transport's endpoint. README documents the transports.
- CI `contract` job: matrix `transport` (Java, Python `streamable`; Rust `sse`) passed as `TICKETS_MCP_TRANSPORT`. Also, as requested by stream C: the Java entry runs `./.github/actions/maven-seed` (`id: seed`) before the image build, and `docker/build-push-action` gets `build-contexts: maven-seed=<dir>` for Java plus `timeout-minutes: 20`.
- `.mcp.json`: `"type": "http"`, `http://localhost:8081/mcp` and `http://localhost:8082/mcp` (headers unchanged).
- Verified: `./mvnw -pl request-log-mcp-server,ticket-mcp-server-java -am verify` green (79 + 102 tests, coverage, format). Python server: ruff check/format + 44 tests. Contract suite with `MCP_SERVICE_TOKEN`: Java jar (`local`) and Python server with `streamable` 69 passed, 1 skipped (the raw SSE test); Rust image with `sse` 69 passed, 1 skipped (the raw Streamable test). The Java image on PostgreSQL was not run locally (CI does).

**Needs from stream A**
- A15: connect with `spring.ai.mcp.client.streamable-http.connections.{tickets,requestlog}` to `${TICKETS_MCP_URL}` / `${REQUEST_LOG_MCP_URL}` with endpoint `/mcp`, keeping the K5 bearer headers. The servers are stateless (no session id, `GET /mcp` is 405), so reconnecting is just retrying the POST; any replica can answer.

**Needs from stream C**
- Compose: nothing required (healthchecks use `/actuator/health/readiness`; the `rust` profile comment already says contract tests and MCP Inspector only).
- Helm (C11a): the MCP servers can scale horizontally without session affinity (stateless). No Helm template for Rust (D2).
- `CLAUDE.md`: the contract command can take `TICKETS_MCP_TRANSPORT=sse` for the Rust server (default `streamable`).

**README / docs needs (C15)**
- README: "Ticket server implementations" table marks Rust as legacy (SSE, reference only); MCP Inspector against `http://localhost:8082/mcp` (Streamable HTTP) instead of `/sse`; `spring.ai.mcp.client.sse.connections.requestlog.url` mention becomes the Streamable HTTP property (after A15).
- HOW_IT_WORKS.md: the server is served at `/mcp` (stateless Streamable HTTP), not `/sse` and `/mcp/message`; the troubleshooting row "check with `curl -N localhost:8082/sse`" becomes e.g. `curl -s localhost:8082/mcp -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'` (plus the bearer token if set).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
B13 – dependencies satisfied: no (⛓ C11c: the k6 load test runs against staging). It is the last stream B task.

## Blocked on
C11c for B13. B10 itself waits only for the S2 merge order with A15.

## Notes for the next session
- Transport (K6): Java servers use `spring.ai.mcp.server.protocol=STATELESS` (not `STREAMABLE`, which keeps sessions in memory). Stateless answers `application/json`, no `Mcp-Session-Id`, `GET /mcp` 405, `DELETE /mcp` 404 (Java) / 405 (Python). Python uses `StreamableHTTPSessionManager(stateless=True, json_response=True)`; its `run()` must be inside the Starlette lifespan, so Python tests use `with TestClient(app) as client`.
- Contract runs: `TICKETS_MCP_TRANSPORT=sse` for Rust; default `streamable` for Java/Python. Rust image locally: `docker build -t b10-ticket-rust ticket-mcp-server-rust && docker run --rm -p 18081:8081 -e MCP_SERVICE_TOKEN=contract-only-token b10-ticket-rust`.
- Contract tests on port 18081 (8081 is the user's compose stack): Java `MCP_SERVICE_TOKEN=contract-only-token java -jar ticket-mcp-server-java/target/ticket-mcp-server-java-*.jar --server.port=18081 --spring.profiles.active=local`, Python `PORT=18081 MCP_SERVICE_TOKEN=contract-only-token uv run --project ticket-mcp-server-python ticket-server`, then `TICKETS_MCP_URL=http://localhost:18081 MCP_SERVICE_TOKEN=contract-only-token uv run pytest`. Free the port with `lsof -ti tcp:18081 | xargs kill`.
- The MCP Java SDK client (`io.modelcontextprotocol.client.*`) is on the servers' classpath through the server starter, so server tests can use a real client without a new dependency.
- No local Rust toolchain: run Rust checks in Docker (see the B9 notes in git history of this file) if Rust code changes. B10 changed only the Rust README.
- Trivy locally: `docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v <dir with trivyignore.yaml>:/cfg:ro -v helpdesk-trivy-cache:/root/.cache aquasec/trivy:latest image --scanners vuln --pkg-types os,library --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 --ignorefile /cfg/trivyignore.yaml <image>`.
