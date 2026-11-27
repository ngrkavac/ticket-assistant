# Stream b handoff – 2026-11-27
## Done this session
- Chore requested by stream C (PR #50): `PACKAGE_REFRESH` build arg in `ticket-mcp-server-python/Dockerfile` – branch `prod/b-python-package-refresh` (base `main` at e2a22b6)

## Ready to push
### Branch `prod/b-python-package-refresh` (base: `main`)
**PR title:** Python ticket server image: PACKAGE_REFRESH cache key for apt upgrade

**PR body:**
Follow-up requested by stream C in PR #50 (images.yml passes `PACKAGE_REFRESH=<UTC date>` as a build arg).

- `ticket-mcp-server-python/Dockerfile`: `ARG PACKAGE_REFRESH=unset` in the runtime stage, immediately before the `RUN apt-get update && apt-get upgrade …` step. The value is unused except as a cache key, so the upgrade layer is not restored from the layer cache with a package index older than a day.
- Verified locally: `docker build --build-arg PACKAGE_REFRESH=2026-11-27 -t b-pyrefresh ticket-mcp-server-python` twice (second build: upgrade step `CACHED`), then with `PACKAGE_REFRESH=2026-11-28` (upgrade step reruns). `uv run ruff check .`, `uv run ruff format --check .`, `uv run pytest` (44 passed).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
B13 – dependencies satisfied: no (⛓ C11c: the k6 load test runs against staging). It is the last stream B task.

## Blocked on
C11c for B13.

## Notes for the next session
- B10 is merged and ticked in the plan (PR #53); its PR text was removed from this file.
- Python Dockerfile: `ARG PACKAGE_REFRESH` sits in the runtime stage right before the `apt-get upgrade` RUN; images.yml passes the UTC date. Changing the value reruns that layer (verified locally with BuildKit), same value keeps it CACHED.
- Transport (K6): Java servers use `spring.ai.mcp.server.protocol=STATELESS` (not `STREAMABLE`, which keeps sessions in memory). Stateless answers `application/json`, no `Mcp-Session-Id`, `GET /mcp` 405, `DELETE /mcp` 404 (Java) / 405 (Python). Python uses `StreamableHTTPSessionManager(stateless=True, json_response=True)`; its `run()` must be inside the Starlette lifespan, so Python tests use `with TestClient(app) as client`.
- Contract runs: `TICKETS_MCP_TRANSPORT=sse` for Rust; default `streamable` for Java/Python. Rust image locally: `docker build -t b10-ticket-rust ticket-mcp-server-rust && docker run --rm -p 18081:8081 -e MCP_SERVICE_TOKEN=contract-only-token b10-ticket-rust`.
- Contract tests on port 18081 (8081 is the user's compose stack): Java `MCP_SERVICE_TOKEN=contract-only-token java -jar ticket-mcp-server-java/target/ticket-mcp-server-java-*.jar --server.port=18081 --spring.profiles.active=local`, Python `PORT=18081 MCP_SERVICE_TOKEN=contract-only-token uv run --project ticket-mcp-server-python ticket-server`, then `TICKETS_MCP_URL=http://localhost:18081 MCP_SERVICE_TOKEN=contract-only-token uv run pytest`. Free the port with `lsof -ti tcp:18081 | xargs kill`.
- The MCP Java SDK client (`io.modelcontextprotocol.client.*`) is on the servers' classpath through the server starter, so server tests can use a real client without a new dependency.
- No local Rust toolchain: run Rust checks in Docker (see the B9 notes in git history of this file) if Rust code changes. B10 changed only the Rust README.
- Trivy locally: `docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v <dir with trivyignore.yaml>:/cfg:ro -v helpdesk-trivy-cache:/root/.cache aquasec/trivy:latest image --scanners vuln --pkg-types os,library --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 --ignorefile /cfg/trivyignore.yaml <image>`.
