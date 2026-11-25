# Helpdesk Assistant

Small Spring Boot + Spring AI project to try **MCP** and **RAG** with a practical use case:
an IT helpdesk assistant.

| Module | Role |
|---|---|
| `ticket-mcp-server-java` / `-python` / `-rust` (port 8081) | **MCP server** exposing per-customer ticket tools `listCustomers`, `getTicket`, `listOpenTickets`, `listTickets` and `createTicket` (demo tickets for acme, globex and initech; the Java server stores them in PostgreSQL, Python and Rust in memory) over SSE. Three interchangeable implementations of the same contract; run one of them (see [Ticket server implementations](#ticket-server-implementations)) |
| `request-log-mcp-server` (port 8082) | **MCP server** backed by a database (PostgreSQL in compose, in-memory H2 by default) that stores every `/ask` request. Tools: `logRequest`, `getRequest`, `listRecentRequests`, plus `listQuestions` / `addQuestion` for the UI's question list |
| `assistant` (port 8080) | **MCP client** + **RAG**: markdown KB in `assistant/src/main/resources/kb` is embedded locally (ONNX, no API key) into an in-memory `SimpleVectorStore`; a local Ollama model (llama3.1, free) answers via `QuestionAnswerAdvisor` and can call the MCP tools |

Stack: Java 21, Spring Boot 3.5, Spring AI 1.1.8, Maven, Ollama (no paid API). The alternative ticket servers use Python 3.12 + the MCP Python SDK (uv) and Rust + axum (cargo).

## Run

```bash
brew install ollama && ollama serve &   # or install the Ollama app
ollama pull llama3.1                      # ~5 GB, supports tool calling
./mvnw -q -pl ticket-mcp-server-java spring-boot:run # terminal 1 (or the Python/Rust server, see below)
./mvnw -q -pl request-log-mcp-server spring-boot:run # terminal 2 (in-memory H2 unless REQUEST_LOG_DB_URL is set)
SPRING_PROFILES_ACTIVE=local ./mvnw -q -pl assistant spring-boot:run   # terminal 3 (first start downloads the embedding model)
```

The `local` profile skips the token check (optionally guarded by `HELPDESK_API_KEY`, sent as `X-API-Key`). Without it the assistant needs `OIDC_ISSUER_URI` and a bearer token on every request; see "Local sign-in (Keycloak)" below.

## Try it

```bash
# Local profile (see above); drop the X-API-Key header if HELPDESK_API_KEY is not set.
# Against compose, send "Authorization: Bearer $TOKEN" instead (see "Local sign-in (Keycloak)").
# RAG only (answer comes from KB)
curl -s localhost:8080/ask -H "X-API-Key: $HELPDESK_API_KEY" -H 'Content-Type: application/json' -d '{"question":"My VPN keeps dropping, what should I try?"}'
# MCP only (live ticket data)
curl -s localhost:8080/ask -H "X-API-Key: $HELPDESK_API_KEY" -H 'Content-Type: application/json' -d '{"question":"What is the status of globex ticket T-1003?"}'
# Both: RAG + MCP
curl -s localhost:8080/ask -H "X-API-Key: $HELPDESK_API_KEY" -H 'Content-Type: application/json' -d '{"question":"What open tickets does acme have, and how do I fix the first one?"}'
```

## Ticket server implementations

The ticket MCP server exists in three languages. Each one is its own module with its own build tool and Dockerfile. They have the same wire contract (server name `ticket-server`, tool names and schemas, compact JSON results, identical error texts, `/actuator/health/readiness`) and all listen on port 8081, so the assistant and `.mcp.json` work with any of them unchanged.

| Module | Stack | Run locally | Test |
|---|---|---|---|
| `ticket-mcp-server-java` | Spring AI (`@Tool` methods), Maven | `./mvnw -q -pl ticket-mcp-server-java spring-boot:run` | `./mvnw -pl ticket-mcp-server-java verify` |
| `ticket-mcp-server-python` | MCP Python SDK (low-level server) + Starlette/uvicorn, uv | `cd ticket-mcp-server-python && uv run ticket-server` | `uv run pytest && uv run ruff check .` |
| `ticket-mcp-server-rust` (**legacy**, SSE reference only, never deployed) | axum + tokio, hand-written SSE transport, cargo | `cd ticket-mcp-server-rust && cargo run` | `cargo test && cargo clippy` |

`ticket-mcp-contract-tests/` is a black-box test suite that every implementation must pass. It connects over SSE like the assistant does. Start any implementation on :8081, then run:

```bash
cd ticket-mcp-contract-tests && TICKETS_MCP_URL=http://localhost:8081 uv run pytest
```

### Tools

Tickets are stored per customer (customer name -> tickets). Customer names are matched case-insensitively after trimming. Ticket ids (`T-1001`, ...) are unique across all customers, but a ticket is only visible through its own customer.

| Tool | Arguments | Result |
|---|---|---|
| `listCustomers` | none | known customer names, sorted |
| `getTicket` | `customer`, `id` | the ticket; `Ticket <id> not found` if that customer has no such ticket (also when another customer owns it) |
| `listOpenTickets` | `customer` | the customer's OPEN and IN_PROGRESS tickets, by id |
| `listTickets` | `customer`, optional `status` (`OPEN`, `IN_PROGRESS`, `RESOLVED`) | all of the customer's tickets, optionally filtered |
| `createTicket` | `customer`, `subject` (max 200 characters), `priority` (`LOW`, `MEDIUM`, `HIGH`) | the new OPEN ticket; the server assigns the next id. Only known customers. Created tickets live in memory until restart |

The seed data is 10 tickets (T-1001..T-1010) for acme, globex and initech. `createTicket` is denied for Claude Code in `.claude/settings.json`, so developers get read-only access like the request log.

The expected values come from the original Java server. They include its Java-specific details: `String.trim()` and `isBlank()` semantics, the ASCII-only `\d`, the coercion of JSON numbers and booleans to strings, and a JSON-RPC error for an unknown tool. The Python and Rust code copies these on purpose. CI builds each image, starts it, and runs this suite against it.

The Python and Rust servers read `PORT` (default `8081`) and `HOST` (default `0.0.0.0`).

## Web UI (`ui/`)

Minimal React + TypeScript (Vite) page, built from the Figma frame in `FIGMA_IMPLEMENTATION_PLAN.md` and extended in `CUSTOMER_QUESTIONS_UI_PLAN.md`:

- **Customer** dropdown, filled from the ticket server (`GET /customers`). Every question is asked for this customer.
- **Questions** checklist, loaded from the request-log database (`GET /questions`). Tick one or more and press **Add**: they are asked one after another, and each answer becomes a row in the history.
- **New question**: type a question and press **Add to list**. It is stored in the database (`POST /questions`), so every browser sees it, and it is ticked, ready to ask.
- **History** shows the last 5 requests for the selected customer (`GET /requests?customer=…&limit=5`).

```bash
cd ui && npm install
npm run dev                          # http://localhost:5173, against an assistant in the `local` profile
npm run lint && npm test && npm run build   # ESLint, Vitest + Testing Library, type check and build (CI job `ui`)
```

The Vite dev server proxies `/ask`, `/requests`, `/customers` and `/questions` to the assistant (`ASSISTANT_URL`, default `http://localhost:8080`). There is no sign-in there, so run the assistant with the `local` profile; if it has a `HELPDESK_API_KEY`, set the same value for `npm run dev` (shell env or `ui/.env.local`) and the proxy adds it as `X-API-Key`. Without oauth2-proxy the UI shows no user menu.

In Docker Compose the UI is behind **oauth2-proxy** on http://localhost:5173 (loopback only), and you sign in with a Keycloak demo user (see "Local sign-in (Keycloak)"). The request path (contract K2):

```
browser --cookie--> oauth2-proxy :5173 --X-Forwarded-Access-Token--> ui (nginx) --Authorization: Bearer--> assistant
```

- oauth2-proxy (`quay.io/oauth2-proxy/oauth2-proxy`) signs the user in at Keycloak (client `helpdesk-ui`, authorization code + PKCE), keeps the tokens in an encrypted session cookie (`resolva_session`), refreshes the access token before it expires (`OAUTH2_PROXY_COOKIE_REFRESH=4m`, tokens live 5 minutes) and passes it upstream. The browser never sees a token.
- `ui/Dockerfile` builds the static files and serves them with unprivileged nginx. nginx forwards `/ask`, `/requests`, `/customers` and `/questions` to `assistant:8080` with `Authorization: Bearer <access token>` (`ui/nginx.conf.template`). It always overwrites `Authorization` and drops cookies, `X-API-Key` and the `X-Forwarded-*` user headers, but it trusts the token header it receives, so the `ui` service is not published: only oauth2-proxy may reach it.
- Without a session, page loads go straight to the Keycloak login page and API calls get 401. On a 401 the UI reloads through `/oauth2/sign_in?rd=<current page>` (at most once a minute, so a token the assistant rejects cannot cause a redirect loop).
- The user menu (top right) shows the name, e-mail and roles from `/oauth2/userinfo`. **Sign out** goes to `/oauth2/sign_out`, which clears the cookie and also ends the Keycloak session (`OAUTH2_PROXY_BACKEND_LOGOUT_URL`), so the next visit asks for the password again.
- Users without `helpdesk-admin` see the "New question" form disabled, because the assistant lets only admins add catalog questions (K3).

Stop `npm run dev` first if it is running, because both use port 5173.

UI endpoints on the assistant (all need a bearer token, or the `local` profile):

| Endpoint | Returns |
|---|---|
| `POST /ask` `{"question", "customer"?}` | `{"answer"}`. When `customer` is given it must be known to the ticket server (400 `Unknown customer` otherwise). The model receives `Customer: <name>` in front of the question; the log stores the question as typed plus the customer |
| `GET /requests?limit=20&customer=acme` | `requestId`, `question`, `status`, `customer`, `createdAt` of the newest requests (limit 1-100), only that customer's when `customer` is given (`listRecentRequests`). Answers and error details are not returned |
| `GET /customers` | customer names from the ticket server's `listCustomers` tool (503 if it is down) |
| `GET /questions` | `[{"id", "text"}]` from the request-log server's `listQuestions` tool |
| `POST /questions` `{"text"}` | 201 with `{"id", "text"}` (`addQuestion`). Max `helpdesk.max-question-length` characters; adding the same text again returns the existing question |

The request-log endpoints return 503 if the log server is unavailable or logging is disabled.

## Request log

After every `/ask` that reaches the model, the assistant calls the `logRequest` tool on `request-log-mcp-server` (MCP connection `requestlog`) with the request id (same as the `X-Request-Id` header), question, customer (if any), answer, status (`SUCCESS`/`ERROR`), error class and duration. The call runs in the background: if the log server is slow or down, the user still gets the answer and the assistant logs a warning (without the question text). Requests rejected before the model call (401, 400, 429) are not stored. `logRequest` is idempotent per request id.

The request-log tools are filtered out of the model's tool list (`McpToolFilter` in `ChatConfig`), so users cannot read other users' questions or write fake entries through the chat. Read the log from any MCP client (e.g. MCP Inspector against `http://localhost:8082/sse`) with `getRequest` / `listRecentRequests` / `listQuestions`, or query the `request_log` and `question_catalog` tables directly. `addQuestion` is denied for Claude Code in `.claude/settings.json`, like `logRequest`. The schema is managed by Flyway (`request-log-mcp-server/src/main/resources/db/migration`).

Config: `REQUEST_LOG_MCP_URL` (assistant, default `http://localhost:8082`), `REQUEST_LOG_ENABLED=false` to turn logging off (the connection is still opened at startup; remove `spring.ai.mcp.client.sse.connections.requestlog.url` to drop it entirely), and on the server `REQUEST_LOG_DB_URL`, `REQUEST_LOG_DB_USER`, `REQUEST_LOG_DB_PASSWORD`, optionally `REQUEST_LOG_DB_MIGRATION_USER` / `REQUEST_LOG_DB_MIGRATION_PASSWORD` (a separate Flyway user; the app user then gets DML only) and `REQUEST_LOG_RETENTION` (default `P90D`).

Add or edit `.md` files in `kb/` to change the knowledge base (re-indexed on each start). To use files outside the jar, set `KB_LOCATION` (`helpdesk.kb.location`), e.g. `KB_LOCATION='file:/data/kb/*.md'` with a mounted volume; no rebuild needed.

## Code formatting

Java code follows [Google Java Style](https://google.github.io/styleguide/javaguide.html), enforced by [google-java-format](https://github.com/google/google-java-format) (version pinned in `pom.xml` as `google-java-format.version`).

- **Pre-commit hook**: enable it once per clone with `git config core.hooksPath .githooks`. It formats the staged `.java` files and re-stages them. The first run downloads the formatter jar into `.git/gjf/`. Files that have both staged and unstaged changes are refused, so unstaged work never slips into a commit. Skip it for one commit with `SKIP_GJF=1 git commit ...`.
- **Format everything by hand**: `./mvnw com.spotify.fmt:fmt-maven-plugin:format`.
- **CI**: `./mvnw verify` runs `fmt-maven-plugin:check` and fails on any unformatted file.
- **Claude Code**: the `google-java-format` skill is in `.claude/skills/`.

## Production notes

| Area | What is in place |
|---|---|
| API | `POST /ask` with bean validation, configurable max length (`helpdesk.max-question-length`), RFC 7807 errors; upstream failures return a generic 503 (no internals leaked) |
| Auth | Every app endpoint needs a bearer JWT from `OIDC_ISSUER_URI` with audience `OIDC_AUDIENCE` (default `helpdesk-assistant`); `OIDC_JWK_SET_URI` optionally fetches the keys from another URL; `OIDC_ROLES_CLAIM` / `OIDC_CUSTOMERS_CLAIM` name the claims (defaults `roles` / `customers`). The UI signs users in through oauth2-proxy (see "Web UI"). `HELPDESK_API_KEY` (`X-API-Key`) only works in the `local` profile. The MCP servers have no auth: keep them on a private network (compose publishes neither them nor PostgreSQL) |
| Prompt safety | System prompt treats user/ticket text as data; ticket tool inputs are validated |
| Resilience | Virtual threads, max 8 concurrent LLM calls (`helpdesk.max-concurrent-requests`; extra requests get 429 + `Retry-After`), HTTP connect/read timeouts for the LLM, 20s MCP request timeout, graceful shutdown. LLM/MCP failures map to 503, our own bugs to 500. The assistant needs the MCP server reachable at startup (compose enforces ordering) |
| Observability | Actuator on a separate management port (`9090`, not published in compose): `health` (liveness/readiness; readiness includes the KB-indexed check), `metrics`, `prometheus`; `X-Request-Id` header and request id in every log line; question text is never written to application logs (it is stored in the request-log database, see above) |
| Config | Via env: `OLLAMA_URL`, `OLLAMA_MODEL`, `TICKETS_MCP_URL`, `REQUEST_LOG_MCP_URL`, `REQUEST_LOG_ENABLED`, `OIDC_ISSUER_URI`, `OIDC_AUDIENCE`, `OIDC_JWK_SET_URI`, `OIDC_ROLES_CLAIM`, `OIDC_CUSTOMERS_CLAIM`, `HELPDESK_API_KEY` (`local` profile only), `KB_LOCATION`, `MANAGEMENT_PORT`, `EMBEDDING_CACHE_DIR`; request-log server: `REQUEST_LOG_DB_URL`, `REQUEST_LOG_DB_USER`, `REQUEST_LOG_DB_PASSWORD`, `REQUEST_LOG_DB_MIGRATION_USER`, `REQUEST_LOG_DB_MIGRATION_PASSWORD`, `REQUEST_LOG_RETENTION`; Java ticket server: `TICKETS_DB_URL`, `TICKETS_DB_USER`, `TICKETS_DB_PASSWORD` (required outside the `local` profile) |
| Packaging | Multi-stage `Dockerfile` for the Java modules (layered jar, non-root user, `--build-arg MODULE=...`); `ticket-mcp-server-python/Dockerfile` and `ticket-mcp-server-rust/Dockerfile` (non-root); `ui/Dockerfile` (static build on unprivileged nginx with the API proxy); `docker-compose.yml` (Ollama, PostgreSQL for the request log and the tickets, request log, assistant, web UI behind oauth2-proxy, Keycloak, and the ticket server chosen by profile); GitHub Actions CI (Maven, Python, Rust and a contract-test matrix over the three ticket servers) |
| Embeddings | ONNX model is cached in `EMBEDDING_CACHE_DIR` (`/models` volume in compose), so restarts do not re-download it. The very first start still needs internet |
| Tests | `./mvnw test`: tool validation, controller tests (auth, validation, error mapping) and a full-context integration test with fake models (KB indexing, retrieval, context reaching the prompt); no LLM needed. `./mvnw verify` also enforces 80% line coverage (JaCoCo, `*Application` classes excluded); report in `<module>/target/site/jacoco` |

Run everything in containers:

```bash
REQUEST_LOG_DB_PASSWORD=change-me-too COMPOSE_PROFILES=java docker compose up --build
open http://localhost:5173   # web UI: sign in as acme-agent / local-only-password
```

For curl, get a token first (see "Local sign-in (Keycloak)"). The assistant is published on `127.0.0.1:8080` only.

All images are pinned to a version tag plus digest (`docker-compose.yml`, `Dockerfile`, `ui/Dockerfile`), and Renovate updates them. Ollama pulls the fixed model tag `llama3.1:8b-instruct-q4_K_M` (the same model as `llama3.1`); change it for both `ollama-pull` and the assistant with `OLLAMA_MODEL`. Compose also sets the MCP service tokens `TICKETS_MCP_TOKEN` and `REQUEST_LOG_MCP_TOKEN` (local-only defaults); they take effect once the servers check them.

`COMPOSE_PROFILES` picks the ticket server: `java`, `python` or `rust`. Exactly one must be active, because the assistant needs a ticket server at startup.

- `java` (use this one): tickets in PostgreSQL, in the `tickets-db` service (database `tickets`, volume `ticketsdata`, not published). The server runs with `SPRING_PROFILES_ACTIVE=local`, so the demo customers and tickets are added on the first start. `TICKETS_DB_PASSWORD` defaults to the local-only `local-only-tickets-db-password`; set it before the volume is first created to use another one. `docker compose down -v` resets the tickets. Look at them with `docker compose exec tickets-db psql -U tickets -d tickets -c "SELECT id, customer, status, subject FROM ticket ORDER BY id;"`.
- `python`: demo tickets in memory, lost on restart.
- `rust`: **legacy** reference implementation (in memory, SSE transport only, never deployed). Once the assistant switches to Streamable HTTP it can no longer use this server; the profile is then only for running the contract tests and MCP Inspector against it.
 Put `COMPOSE_PROFILES=java` in `.env` to make it the default, and override it per run with `COMPOSE_PROFILES=rust docker compose up --build`. Each implementation answers to the network alias `ticket-mcp-server`. To switch a running stack, run `docker compose down` first so the old server stops.

### Local sign-in (Keycloak)

Compose also starts Keycloak 26 in dev mode at http://localhost:8180 (loopback only), with the realm `helpdesk` from `deploy/keycloak/realm-helpdesk.json`. **Everything in it is for local development only**: the users, their password and the default secrets below are public. Never import this realm into a shared or production Keycloak.

| User | Roles | `customers` claim |
|---|---|---|
| `acme-agent` | `helpdesk-agent` | `acme` |
| `globex-agent` | `helpdesk-agent` | `globex` |
| `acme-writer` | `helpdesk-agent`, `tickets-write` | `acme` |
| `admin` | `helpdesk-agent`, `helpdesk-admin`, `tickets-write` | `*` (all customers) |

All four use the password `local-only-password`. Access tokens have `aud=helpdesk-assistant` and the claims `roles`, `customers`, `sub` and `preferred_username`. The issuer is always `http://localhost:8180/realms/helpdesk`, also when another container calls `http://keycloak:8080`; discovery then returns `http://keycloak:8080/...` for the JWKS and token endpoints.

- Clients: `helpdesk-ui` (confidential, authorization code + PKCE, for oauth2-proxy; secret `HELPDESK_UI_CLIENT_SECRET`, default `local-only-client-secret`), `helpdesk-dev-cli` (public, password grant, for curl and tests only) and `helpdesk-assistant` (the audience; no login).
- Admin console: http://localhost:8180/admin, user `admin`, password `KEYCLOAK_ADMIN_PASSWORD` (default `local-only-admin`). This `admin` is in the `master` realm and is not the `admin` demo user above.
- The realm is imported when the container is created; it is skipped if the realm already exists. After editing the JSON, recreate the container: `docker compose up -d --force-recreate keycloak`.

Get a token for a demo user:

```bash
curl -s localhost:8180/realms/helpdesk/protocol/openid-connect/token \
  -d grant_type=password -d client_id=helpdesk-dev-cli \
  -d username=acme-agent -d password=local-only-password | jq -r .access_token
```

Call the assistant with it:

```bash
TOKEN=$(curl -s localhost:8180/realms/helpdesk/protocol/openid-connect/token \
  -d grant_type=password -d client_id=helpdesk-dev-cli \
  -d username=acme-agent -d password=local-only-password | jq -r .access_token)
curl -s localhost:8080/ask -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"How do I reset my password?","customer":"acme"}'
```

In compose the assistant validates the tokens with `OIDC_ISSUER_URI=http://localhost:8180/realms/helpdesk` and fetches the keys from `OIDC_JWK_SET_URI=http://keycloak:8080/realms/helpdesk/protocol/openid-connect/certs`, because the issuer URL is not reachable from inside the container.

The web UI signs in through oauth2-proxy with the `helpdesk-ui` client. oauth2-proxy skips OIDC discovery for the same reason: it sends the browser to `localhost:8180` to log in and calls `keycloak:8080` for the token, keys, user info and logout. Its local-only defaults: `HELPDESK_UI_CLIENT_SECRET` (shared with the realm) and `OAUTH2_PROXY_COOKIE_SECRET` (default `local-only-cookie-secret-32bytes`; it must be 16, 24 or 32 bytes). The realm only accepts the redirect URI `http://localhost:5173/oauth2/callback`, so open the UI on exactly that host and port.

Known limits (deliberate for a demo): in-memory vector store re-indexed on each start, no retention/purge for the request log (questions are kept indefinitely: add a cleanup job if they may contain personal data), no rate limiting or TLS (put a gateway/ingress in front), Docker setup not built or run here.
