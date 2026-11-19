# Production Readiness Plan — Helpdesk Assistant (Resolva)

> Findings and phased tasks (T-IDs) are below. The **task list for 3 parallel agents** is in [Work breakdown](#work-breakdown-3-parallel-streams) at the end.

## Context

The project is a well-built demo: Spring Boot assistant (RAG + MCP client), a Postgres-backed request-log MCP server, three interchangeable ticket MCP servers (Java/Python/Rust), and a React UI behind nginx. It already has RFC 7807 errors, a concurrency cap, graceful shutdown, separate actuator port, non-root images, 80% coverage gate, formatting gate and contract tests.

What keeps it from production is mostly **trust boundaries, data, and operations**, not code quality:

1. **No user identity / no tenant isolation.** One shared `X-API-Key` is injected by nginx, so anyone who reaches the UI can read any customer's tickets and history. The customer is only a hint in the prompt (`AssistantService.userMessage`, `"Customer: acme"`); the LLM can be talked into calling ticket tools for another customer.
2. **Fail-open security defaults.** Blank `HELPDESK_API_KEY` = everything open (`ApiKeyFilter`); new endpoints are unprotected unless added to the `PROTECTED` list; MCP servers have no auth.
3. **Demo data.** Tickets are in-memory and lost on restart; request-log silently falls back to in-memory H2 when `REQUEST_LOG_DB_URL` is unset.
4. **Fragile runtime coupling.** Assistant must reach both MCP servers at startup; readiness doesn't reflect MCP health; MCP over the deprecated SSE transport; background log writes use an unbounded executor.
5. **No deployment story.** Compose only, `ollama/ollama:latest`, images never published, no K8s manifests, no TLS, embedding model downloaded from the internet on first start.
6. **Gaps in CI/supply chain/observability.** UI is not built/tested in CI; no image scanning, SBOM, dependency updates; plain-text logs; no tracing; Prometheus only on the assistant.
7. **Functional gaps.** The UI never shows the model's answer (`App.tsx:96` discards `ask()`'s result; history shows question/status only).

**Decisions (confirmed with user):** deploy to **Kubernetes**; authenticate with **OIDC SSO**; keep **self-hosted Ollama**.

**Recommendations taken as defaults (flag if you disagree):**
- Production ticket server = `ticket-mcp-server-java`, backed by Postgres. Python stays a contract-tested reference implementation; Rust stays a legacy SSE-only reference (decision D2). Neither is deployed.
- Auth pattern = **oauth2-proxy** in front of the UI (session cookie, no tokens in the browser) forwarding the access token; **assistant validates the JWT** as an OAuth2 resource server.
- Keep the in-memory `SimpleVectorStore` (KB is 3 files); load the KB from a mounted ConfigMap. Move to pgvector only when the KB grows past a few thousand chunks.

---

## Findings (by area, with file references)

### A. Security & tenancy (blocking)
| # | Finding | Where |
|---|---|---|
| A1 | Shared API key; no user identity; any key holder sees all customers | `ApiKeyFilter.java`, `ui/nginx.conf.template:10` |
| A2 | Fail-open: blank key disables auth; protection is a path allowlist (`PROTECTED`), so new endpoints are public by default | `ApiKeyFilter.java:27-38` |
| A3 | Customer scoping enforced only by the prompt; LLM can call `getTicket/listTickets/createTicket` for any customer. A user can also type `Customer: globex` inside the question | `AssistantService.java:66-68`, `ChatConfig.java` |
| A4 | `createTicket` (a write) is triggered by the model with no role check or audit trail | `ChatConfig.java` tool wiring, `TicketTools.createTicket` |
| A5 | MCP servers unauthenticated; no Origin validation (MCP spec requires it for HTTP transports) | ticket/request-log servers |
| A6 | `/requests` and `/questions` are not authorized per user/customer; anyone can add catalog questions | `RequestHistoryController`, `QuestionController` |
| A7 | No security headers (CSP, `X-Content-Type-Options`, `frame-ancestors`, HSTS) on UI or API | `nginx.conf.template`, assistant |
| A8 | No per-user rate limit (only a global per-pod semaphore) | `AssistantService.java:37` |

### B. Data & persistence (blocking)
| # | Finding | Where |
|---|---|---|
| B1 | Tickets are in-memory seed data; `createTicket` lost on restart; id generation scans all tickets under a global lock | `TicketTools.java` |
| B2 | Request-log silently uses H2 when DB URL unset — a misconfigured prod pod "works" and loses data | `request-log-mcp-server/.../application.properties:9` |
| B3 | Questions/answers (likely PII) kept forever; no retention/purge | README "Known limits" |
| B4 | Flyway runs as the app DB user (DDL rights at runtime); no backups defined | request-log server, compose |
| B5 | **Bug:** `helpdesk.max-question-length` may be set up to 10000, but `question_catalog.text` is `VARCHAR(1000)` and `addQuestion` rejects >1000 → user gets **503** instead of 400 | `HelpdeskProperties.java:15`, `V2__...sql:9`, `RequestLogTools.java:20` |
| B6 | Request-log tests run on H2 only; Postgres behaviour (e.g. `LIMIT` binding, identity) untested | `request-log-mcp-server/src/test` |

### C. Resilience
| # | Finding | Where |
|---|---|---|
| C1 | Assistant fails to start if an MCP server is down; behaviour after an MCP server restart (SSE session lost) is unverified — likely needs an assistant restart | `application.properties:22-25` |
| C2 | Readiness ignores MCP connectivity and Ollama | `RagConfig.knowledgeBase`, `management.endpoint.health.group.readiness.include` |
| C3 | Background log writes use an unbounded virtual-thread executor: a slow log server → unbounded memory; drops are invisible (no metric) | `RequestLogClient.java:54,111` |
| C4 | `requireKnown` calls `listCustomers` over MCP on **every** `/ask` | `TicketDirectoryClient.java:78` |
| C5 | SSE transport is deprecated in the MCP spec (Streamable HTTP replaces it) | all MCP servers/clients |
| C6 | `QUEUE_WAIT_MS` hard-coded; `Retry-After: 5` hard-coded | `AssistantService.java:17`, `ApiExceptionHandler.java:44` |
| C7 | Embedding model downloaded from the internet on first start; pods can't start in an egress-restricted cluster | `application.properties:18-19` |

### D. Observability
| # | Finding | Where |
|---|---|---|
| D1 | Plain-text logs; no JSON structured logging | all services |
| D2 | No distributed tracing assistant → MCP → DB / Ollama | — |
| D3 | Prometheus only on assistant; MCP servers expose `health,info` only | ticket/request-log `application.properties` |
| D4 | `X-Request-Id` always regenerated; incoming ingress id/trace id ignored | `RequestIdFilter.java:24` |
| D5 | No business metrics (LLM latency by outcome, tool calls by name/outcome, 429s, log-write drops), no alerts, no dashboards | — |
| D6 | `/actuator/info` empty (no build/git info) | poms |

### E. Packaging, deployment, supply chain
| # | Finding | Where |
|---|---|---|
| E1 | No K8s manifests/Helm chart; no Ingress/TLS; no NetworkPolicies | — |
| E2 | `ollama/ollama:latest`, model `llama3.1` untagged; base images not pinned by digest | `docker-compose.yml:4,16`, Dockerfiles |
| E3 | **Bug:** Dockerfile globs `${MODULE}-*-SNAPSHOT.jar` — breaks as soon as the version is not a SNAPSHOT | `Dockerfile:13` |
| E4 | Images not built/pushed by CI; no versioning/release process (`0.0.1-SNAPSHOT`) | `.github/workflows/ci.yml` |
| E5 | No image scan, SBOM, Dependabot/Renovate; actions pinned by tag not SHA; workflow has default (write) token permissions | `ci.yml` |
| E6 | No prod Spring profile with fail-fast validation of required config | all `application.properties` |
| E7 | No resource requests/limits; JVM heap sized by `MaxRAMPercentage=75` only | `Dockerfile:23` |

### F. Testing & quality
| # | Finding | Where |
|---|---|---|
| F1 | UI: no lint, no tests, not built in CI | `ui/package.json`, `ci.yml` |
| F2 | No end-to-end smoke test of the full stack | — |
| F3 | No LLM quality regression set (prompt/model changes are untested against real model) | — |
| F4 | No load test to size `max-concurrent-requests` vs Ollama `OLLAMA_NUM_PARALLEL` / GPU | — |

### G. Product gaps that block real use
| # | Finding | Where |
|---|---|---|
| G1 | UI never displays the answer | `ui/src/App.tsx:96`, `HistoryTable.tsx` |
| G2 | UI error text references `HELPDESK_API_KEY`; no login/logout/session-expiry handling | `ui/src/api.ts:29-31` |
| G3 | No user feedback on answers (thumbs up/down) for quality monitoring — optional | — |

---

## Target architecture

```
Browser ──TLS──> Ingress ──> oauth2-proxy ──> ui (nginx, static + /api proxy)
                                   │ (Authorization: Bearer <access token>)
                                   └────────────> assistant (Spring Security resource server)
                                                   ├─ Ollama (GPU node pool, pinned model)
                                                   ├─ ticket-mcp-server-java ──> Postgres (tickets schema)
                                                   └─ request-log-mcp-server ──> Postgres (requestlog schema)
NetworkPolicies: only assistant → MCP servers/Ollama; only MCP servers → Postgres.
MCP calls carry a service token (defense in depth) over Streamable HTTP.
```

Authorization model (JWT claims, names configurable):
- `roles`: `helpdesk-agent` (ask, read history for allowed customers), `helpdesk-admin` (all customers, manage question catalog), `tickets-write` (may create tickets via the assistant).
- `customers`: list of customer ids the user may act for; `*` for admins.

---

## Implementation phases & tasks

Each task: ID · priority (P0 = blocks go-live, P1 = needed for go-live quality, P2 = soon after) · depends on · acceptance criteria.

### Phase 0 — Quick wins & bugs (no architecture change)
- [ ] **T0.1 (P0)** Fix catalog length mismatch (B5): cap `helpdesk.max-question-length` validation at the catalog limit for `/questions` (separate `helpdesk.max-catalog-question-length=1000`), and map request-log validation errors (`isError` with an "invalid" message) to 400 instead of 503. *AC:* 1001-char question → 400 with clear detail; test in `QuestionControllerTests`.
- [ ] **T0.2 (P0)** Fix Dockerfile jar glob (E3): use `${MODULE}/target/${MODULE}-*.jar` excluding `*-plain.jar`, or set `<finalName>app</finalName>` in module poms. *AC:* builds with version `1.0.0`.
- [ ] **T0.3 (P0)** Show the answer in the UI (G1): render `ask()` result per question (answer panel/list), keep history as-is. *AC:* asked questions show answers; errors shown per question.
- [ ] **T0.4 (P1)** Cache `listCustomers` (C4) for 60s (Caffeine via `spring-boot-starter-cache`), invalidate on `UnknownCustomerException` miss once. *AC:* one MCP call per TTL in `TicketDirectoryClientTests`.
- [ ] **T0.5 (P1)** Make `QUEUE_WAIT_MS` and `Retry-After` configurable (`helpdesk.queue-wait`, `helpdesk.retry-after`) in `HelpdeskProperties` (C6).
- [ ] **T0.6 (P1)** Bound the request-log executor (C3): `ThreadPoolExecutor` with virtual-thread factory, bounded queue (e.g. 1000), `AbortPolicy` caught in `record()`; Micrometer counters `helpdesk.requestlog.writes{outcome=stored|failed|dropped}`. *AC:* test that overflow increments `dropped` and never blocks `/ask`.
- [ ] **T0.7 (P1)** Accept incoming `X-Request-Id` when it matches `[A-Za-z0-9._:-]{1,64}`, else generate (D4) — matches `RequestLogTools.requireRequestId` rules.

### Phase 1 — Authentication & authorization (OIDC)  *(P0)*
- [ ] **T1.1** Add `spring-boot-starter-oauth2-resource-server` + `spring-boot-starter-security` to `assistant`. `SecurityFilterChain`: all app endpoints `authenticated()` (deny-by-default, fixes A2), actuator on management port excluded, stateless, CSRF off (bearer only), security headers on. Config: `spring.security.oauth2.resourceserver.jwt.issuer-uri=${OIDC_ISSUER_URI}`, audience validator (`OIDC_AUDIENCE`). Remove `ApiKeyFilter` (keep optional API-key auth only behind a `local` profile for dev/tests).
- [ ] **T1.2** `HelpdeskPrincipal` extraction: `JwtAuthenticationConverter` mapping `roles` claim → authorities, `customers` claim → allowed-customer set (claim names configurable: `helpdesk.security.roles-claim`, `customers-claim`).
- [ ] **T1.3** Authorize endpoints: `/ask` requires `helpdesk-agent`; `customer` is **required** and must be in the user's allowed set (403 otherwise); `/customers` returns only allowed customers; `/requests?customer=` enforced the same way (no "all customers" query unless admin); `POST /questions` requires `helpdesk-admin` (A6).
- [ ] **T1.4 Server-side tool scoping (A3, most important):** wrap every ticket `ToolCallback` in a `CustomerScopedToolCallback` (decorator in `ChatConfig`) that reads the request's customer from Spring AI `ToolContext` (passed via `chat.prompt().toolContext(Map.of("customer", c, "principal", p))`), parses the tool-call JSON, and **overwrites/validates** the `customer` argument; mismatch → tool error "Not allowed for this customer" + WARN log + metric. `listCustomers` is hidden from the model (the UI already selects the customer). Strip any user-typed `Customer:` line ambiguity by sending the customer only via the system/context, not the user text.
- [ ] **T1.5 Write gating (A4):** `createTicket` only offered to the model when the principal has `tickets-write` (filter tool list per request); every created ticket audited in request log (`tool_calls` column or separate `audit_event` table: who, customer, tool, args hash, result id).
- [ ] **T1.6** Store `user_subject` (JWT `sub`) on each request-log row (new Flyway migration `V3__user_subject.sql`), returned to admins only.
- [ ] **T1.7** UI auth: deploy **oauth2-proxy** (Ingress auth or sidecar) with `--pass-access-token`/`--set-authorization-header`; nginx forwards `Authorization` to assistant and drops the `X-API-Key` injection and `HELPDESK_API_KEY` env. UI handles 401 by `window.location = '/oauth2/sign_in'`, adds user menu + logout (`/oauth2/sign_out`), removes the API-key error text (G2).
- [ ] **T1.8** Per-user rate limiting (A8): Bucket4j (in-memory per pod is acceptable initially) keyed by `sub`, e.g. 20 asks/min, 429 + `Retry-After`. Config in `HelpdeskProperties`.
- [ ] **T1.9** Tests: `@WebMvcTest` with `jwt()` post-processor for every endpoint × role × customer; tool-scoping unit tests (model asks for other customer → blocked); integration test in `AssistantIntegrationTests` with a fake model emitting a cross-customer tool call.
- [ ] **T1.10** Local dev: Keycloak in `docker-compose.yml` (`dev` profile) with realm export (`deploy/keycloak/realm-helpdesk.json`) containing demo users per customer + admin; README updated.

### Phase 2 — Real data & database hardening  *(P0)*
- [ ] **T2.1** Persist tickets (B1) in `ticket-mcp-server-java`: JDBC + Flyway (`V1__tickets.sql`: `customer`, `ticket` with `BIGSERIAL`-backed id sequence → `T-<n>`, status/priority CHECK constraints, `created_by`, `created_at`); seed data only via `dev` profile migration (`db/seed`). Keep the tool contract identical so `ticket-mcp-contract-tests` still pass (run them against Postgres via compose in CI). *Decided (D1): own Postgres, no external ticketing adapter.*
- [ ] **T2.2** Fail fast on missing DB (B2): remove the H2 default from main `application.properties`; H2 only in `src/test` and a `local` profile. `prod` profile requires `REQUEST_LOG_DB_URL`.
- [ ] **T2.3** DB least privilege (B4): separate Flyway user (`spring.flyway.user/password`) with DDL rights; app user with DML only on its schema. Separate schemas/DBs: `requestlog`, `tickets`.
- [ ] **T2.4** Retention (B3): `@Scheduled` purge job in request-log server, `request-log.retention=P90D` (configurable), batch deletes, ShedLock (JDBC) so only one replica runs it; metric of rows purged. Document in a data-protection section (what is stored, who can read it, retention).
- [ ] **T2.5** Testcontainers Postgres for request-log and ticket server repository tests (B6); keep H2 out of tests that verify SQL.
- [ ] **T2.6** Hikari tuning & timeouts: `connection-timeout=5s`, `maximum-pool-size` from env, statement timeout via `options=-c statement_timeout=5000` in the JDBC URL.
- [ ] **T2.7** Backups: managed Postgres (preferred) or CloudNativePG operator with scheduled base backups + WAL archiving; documented restore drill.

### Phase 3 — MCP & runtime resilience  *(P1)*
- [ ] **T3.1** Migrate to **Streamable HTTP** (C5): servers `spring.ai.mcp.server.protocol=STREAMABLE` (Java); client `spring.ai.mcp.client.streamable-http.connections.*`. Update `.mcp.json`, contract tests (`mcp` Python SDK streamable client), Python server (SDK supports it). Rust stays SSE-only as a legacy reference (decision D2); the contract tests become transport-selectable so it is still tested.
- [ ] **T3.2** Startup/reconnect tolerance (C1): verify behaviour when an MCP server restarts (test: kill server, call `/ask`). If the Spring AI client does not recover, add a `McpClientSupervisor` that detects transport errors and recreates the `McpSyncClient` (or use stateless Streamable HTTP, which removes the session problem). Allow assistant to start with MCP servers down (`spring.ai.mcp.client.initialized=false` + lazy init) and report not-ready instead of crash-looping.
- [ ] **T3.3** Health (C2): `HealthIndicator`s `ticketsMcp`, `requestLogMcp` (`client.ping()` with 2s timeout, cached 10s) and `ollama` (`GET /api/tags`, model present). Readiness = `readinessState, knowledgeBase, ticketsMcp, ollama`; request-log is **not** in readiness (logging is best-effort by design) but is in `health` details for alerting.
- [ ] **T3.4** Service-to-service auth (A5): shared bearer token per MCP server (`MCP_SERVICE_TOKEN`, from K8s Secret) checked by a filter on `/mcp*` in both Java servers; assistant sends it via a customizer on the MCP HTTP transport. Validate `Origin` header (reject browser origins).
- [ ] **T3.5** Bake the ONNX embedding model into the assistant image (or an init container copying from an OCI artifact) (C7); set `spring.ai.embedding.transformer.onnx.model-uri`/`tokenizer.uri` to local `file:` paths so pods need no internet.
- [ ] **T3.6** Ollama ops: pin image tag + digest, pin model tag (`llama3.1:8b-instruct-q4_K_M`), pre-pull model in a K8s Job onto a PVC; set `OLLAMA_NUM_PARALLEL` and `OLLAMA_KEEP_ALIVE=-1`; align `helpdesk.max-concurrent-requests × assistant replicas ≤ OLLAMA_NUM_PARALLEL × ollama replicas`. Set Ollama `num_ctx` explicitly to fit system prompt + RAG context + tool schemas.
- [ ] **T3.7** KB from ConfigMap/volume: `KB_LOCATION=file:/kb/*.md` in K8s; KB changes roll the deployment (checksum annotation). Add KB-size guard (warn if > N chunks → consider pgvector).

### Phase 4 — Observability  *(P1)*
- [ ] **T4.1** JSON logs (D1): `logging.structured.format.console=ecs` in `prod` profile for both Java servers + assistant; Python `python-json-logger`, Rust `tracing-subscriber` json (if still deployed). Include `requestId`, `traceId`, `sub` (hashed), `customer`; never the question/answer text.
- [ ] **T4.2** Tracing (D2): `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` in all Java services; `management.tracing.sampling.probability` from env; verify `traceparent` propagation over MCP HTTP (add an HTTP request customizer if not automatic). Spring AI chat/tool observations become spans.
- [ ] **T4.3** Prometheus on MCP servers (D3): add `micrometer-registry-prometheus`, expose `prometheus` on a separate management port like the assistant.
- [ ] **T4.4** Business metrics (D5): timers `helpdesk.ask{outcome}`, counters `helpdesk.tool.calls{tool,outcome}`, `helpdesk.tool.denied{tool}`, `helpdesk.ask.rejected{reason=busy|ratelimit}`; Spring AI `gen_ai.client.*` metrics enabled.
- [ ] **T4.5** Alerts (PrometheusRule) & dashboard (Grafana JSON in `deploy/observability/`): 5xx rate > 2%, 429 rate, `/ask` p95 > 30s, readiness flapping, request-log drops > 0, tool denials spike (possible injection attempts), Postgres connection saturation.
- [ ] **T4.6** Build info (D6): `spring-boot-maven-plugin` `build-info` goal + `git-commit-id-maven-plugin`; expose in `/actuator/info`.

### Phase 5 — Kubernetes deployment  *(P0)*
- [ ] **T5.1** Helm chart `deploy/helm/helpdesk/` with sub-templates for `assistant`, `ticket-mcp-server`, `request-log-mcp-server`, `ui`, `ollama` (StatefulSet + PVC, GPU `nodeSelector`/tolerations), `oauth2-proxy` (dependency chart). Values per env (`values-staging.yaml`, `values-prod.yaml`).
- [ ] **T5.2** Probes: `startupProbe` (assistant: KB indexing + model load, up to ~3 min), `readinessProbe` `/actuator/health/readiness`, `livenessProbe` `/actuator/health/liveness` — all on management port.
- [ ] **T5.3** Resources: requests/limits per service (start: assistant 1 CPU/1.5Gi, MCP servers 250m/512Mi, ui 50m/64Mi; tune after T6.4); `-XX:MaxRAMPercentage=75` kept; PodDisruptionBudgets; HPA on assistant (CPU + in-flight requests) bounded by Ollama capacity.
- [ ] **T5.4** Security context: `runAsNonRoot`, `readOnlyRootFilesystem` (emptyDir for `/tmp`, nginx cache), drop all capabilities, `seccompProfile: RuntimeDefault`; dedicated ServiceAccounts with `automountServiceAccountToken: false`.
- [ ] **T5.5** NetworkPolicies: default-deny; ingress → oauth2-proxy/ui; ui → assistant; assistant → MCP servers + Ollama + OTLP; MCP servers → Postgres; egress to OIDC issuer for assistant (JWKS) and oauth2-proxy.
- [ ] **T5.6** Ingress + TLS (cert-manager), HSTS; nginx security headers (A7): CSP `default-src 'self'`, `X-Content-Type-Options nosniff`, `Referrer-Policy`, `frame-ancestors 'none'`; nginx timeouts kept at 180s.
- [ ] **T5.7** Secrets via External Secrets Operator (or Sealed Secrets): DB credentials, MCP service tokens, oauth2-proxy client secret/cookie secret. Nothing secret in values files.
- [ ] **T5.8** `prod` Spring profile (E6) with `@Validated` required properties (issuer URI, DB URL, service tokens) → startup fails fast with a clear message.
- [ ] **T5.9** Keep `docker-compose.yml` for local dev (now with Keycloak + oauth2-proxy), pin all images (E2).

### Phase 6 — CI/CD, supply chain, testing  *(P0 for CI/CD, P1 rest)*
- [ ] **T6.1** UI quality (F1): add ESLint (typescript-eslint, react-hooks), Vitest + Testing Library tests for `App` flows (ask → answer shown, 401 redirect, error states); CI job `ui`: `npm ci && npm run lint && npm test && npm run build`.
- [ ] **T6.2** Image pipeline (E4): on `main` and tags, build all images with Buildx, tag `sha-<short>` + semver on release, push to GHCR; Trivy scan (fail on HIGH/CRITICAL with fix available); CycloneDX SBOM (`cyclonedx-maven-plugin`, `syft` for others) attached; cosign keyless signing.
- [ ] **T6.3** Hardening CI (E5): top-level `permissions: contents: read`; pin actions by SHA; Renovate (or Dependabot) for Maven, npm, uv, cargo, Docker, GitHub Actions; `helm lint` + `kubeconform` job.
- [ ] **T6.4** Load test (F4): k6 script `perf/ask.js` against staging; find the max sustainable `/ask` rate for chosen GPU; record results and set `max-concurrent-requests`, HPA and Ollama parallelism from them.
- [ ] **T6.5** E2E smoke (F2): compose-based CI job using a tiny Ollama model (e.g. `qwen2.5:0.5b`) or a stub Ollama HTTP server; asserts login (Keycloak test user) → `/customers` → `/ask` → history row. Runs on PRs touching wiring.
- [ ] **T6.6** LLM eval set (F3): `eval/questions.yaml` (~30 golden questions: KB, ticket lookup, cross-customer injection attempts, create-ticket gating) with assertions (must mention ticket id, must refuse, must not call tool X); nightly job against the pinned prod model; fail on regression. Required when changing prompt, model, or `top-k`/threshold.
- [ ] **T6.7** Release process: version via tags (`v1.2.3`) → Maven `-Drevision`, `versions` aligned across modules; Helm chart `appVersion`; CHANGELOG; deploy to staging automatically, prod by manual approval (GitHub Environments).

### Phase 7 — Documentation & operations  *(P1)*
- [ ] **T7.1** `docs/RUNBOOK.md`: deploy/rollback, rotating secrets, Ollama model upgrade procedure (eval gate), KB update, DB restore, what each alert means and first actions.
- [ ] **T7.2** `docs/SECURITY.md`: threat model (prompt injection, cross-tenant access, data retention), authz matrix, data inventory (what is stored where, retention, who can read).
- [ ] **T7.3** Update `README.md` "Production notes" and "Known limits" to reflect the new reality; remove API-key instructions except for `local` profile.

---

## Suggested order & milestones
1. **M1 – Safe internal pilot:** Phase 0, Phase 1, T2.1–T2.3, T3.3, T5.1–T5.8, T6.1–T6.3.
2. **M2 – General availability:** rest of Phase 2, Phase 3, Phase 4, T6.4–T6.7, Phase 7.

## Critical files to modify
- Assistant: `ChatConfig.java` (tool scoping decorator, per-request tool filtering), `AssistantService.java` (ToolContext, no customer in user text), `AssistantController.java`, `RequestHistoryController.java`, `QuestionController.java`, `CustomerController.java`, `TicketDirectoryClient.java` (cache), `RequestLogClient.java` (bounded executor, metrics, `sub`), `RequestIdFilter.java`, `ApiKeyFilter.java` (→ `SecurityConfig.java`), `HelpdeskProperties.java`, `ApiExceptionHandler.java`, `application.properties` (+ `application-prod.properties`, `application-local.properties`), `assistant/pom.xml`.
- Request log: `RequestLogTools.java`, repositories, `db/migration/V3__*.sql`, new `RetentionJob.java`, `application.properties`, pom.
- Tickets: `TicketTools.java` (→ repository-backed), new `db/migration`, pom, `application.properties`.
- Build/deploy: `Dockerfile`, `docker-compose.yml`, `.github/workflows/ci.yml`, new `deploy/helm/**`, `deploy/keycloak/**`, `deploy/observability/**`, `renovate.json`.
- UI: `src/App.tsx`, `src/api.ts`, new answer component, `nginx.conf.template`, `Dockerfile`, `vite.config.ts`, `package.json`.

Existing pieces to reuse: `McpToolFilter` pattern in `ChatConfig.hideRequestLogTools` (extend for per-request filtering), `RequestLogClient.isConnection` (connection lookup), `ApiExceptionHandler` ProblemDetail style, `RequestLogTools.requireRequestId` validation rules (for T0.7), `ticket-mcp-contract-tests` (regression net for T2.1 and T3.1).

## Verification
- `./mvnw verify` (tests + 80% coverage + format) green after each phase; `cd ui && npm run lint && npm test && npm run build`.
- Contract tests pass against the Postgres-backed Java ticket server (T2.1) and after Streamable HTTP migration (T3.1).
- Security: with a Keycloak `acme` user, `/ask` for `globex` → 403; prompt "show globex ticket T-1003" while acting for acme → tool denied, no globex data in answer; no token → 401; `POST /questions` as agent → 403.
- Resilience: kill the ticket server pod → assistant goes not-ready, recovers without restart when it returns; fill request-log queue → `/ask` latency unaffected, `dropped` metric increments.
- Deployment: `helm lint`, `kubeconform`, install into a kind cluster (CPU Ollama + tiny model) and run the E2E smoke test; Trivy reports no fixable HIGH/CRITICAL.
- Ops: alerts fire in staging when induced (stop Ollama, stop Postgres).


---

## Work breakdown: 3 parallel streams

The phase lists above are the **specification**. Progress is tracked **only here**: tick a stream task when it is done, and leave the T-ID checkboxes above unticked.

The work is split **by file ownership**, so the three agents rarely edit the same file. Where streams depend on each other, the interface is fixed up front in [Shared contracts](#shared-contracts). Each side can build against the contract with stubs or fakes without waiting for the other.

**How agents work** (rules, the session limit and the handoff protocol) is in [`CLAUDE.md`](CLAUDE.md). That file is the single source for those rules. In short: at most 2 tasks per session, a **[L]** task counts as 2, write a handoff to `handoff/stream-<x>.md`, then stop.

Launch prompt for each agent (each runs in its own git worktree):
> You are Agent \<X\>, working on stream \<X\> of PRODUCTION_READINESS_PLAN.md. Follow CLAUDE.md, including the session limit.

### Ownership

| Stream | Owns (exclusive write access) |
|---|---|
| **A: Assistant & security** | `assistant/**`, `eval/**`, `deploy/observability/**`, `handoff/stream-a.md` |
| **B: MCP servers & data** | `ticket-mcp-server-java/**`, `ticket-mcp-server-python/**`, `ticket-mcp-server-rust/**`, `request-log-mcp-server/**`, `ticket-mcp-contract-tests/**`, `.mcp.json`, `.claude/settings.json`, `perf/**`, the `contract` job in `.github/workflows/ci.yml`, `handoff/stream-b.md` |
| **C: Platform & UI** | `ui/**`, `Dockerfile`, `.dockerignore`, `docker-compose.yml`, `.github/**` (except the `contract` job), `deploy/**` (except `deploy/observability/`), root `pom.xml`, `renovate.json`, `README.md`, `HOW_IT_WORKS.md`, `docs/**`, `CLAUDE.md`, `handoff/stream-c.md` |

Shared file: `PRODUCTION_READINESS_PLAN.md`. Each stream may only tick its own checkboxes. Agents commit locally only; the user pushes and opens every PR (D3, `CLAUDE.md`).

### Shared contracts

| # | Contract | Producer → consumer | Definition |
|---|---|---|---|
| K1 | JWT claims | C (Keycloak realm) → A | Access token with `aud=helpdesk-assistant` and these claims: `roles: string[]` from {`helpdesk-agent`, `helpdesk-admin`, `tickets-write`}; `customers: string[]` (lower-case ids, or `["*"]` for admins); `sub`; `preferred_username`. Claim names are configurable through `helpdesk.security.roles-claim` and `helpdesk.security.customers-claim`. |
| K2 | Auth header path | C (oauth2-proxy, nginx) → A | Browser → oauth2-proxy (cookie session) → nginx → assistant, carrying `Authorization: Bearer <access token>`. nginx no longer sets `X-API-Key`. The UI reads user info from `/oauth2/userinfo` and signs out through `/oauth2/sign_out`. |
| K3 | API changes | A → C (UI) | `POST /ask {question, customer}`: `customer` is now **required** and the response stays `{answer}`. Missing or invalid token → 401. Customer not in the token → 403. `GET /customers` returns only the allowed customers. `POST /questions` requires `helpdesk-admin` (403 otherwise). A catalog question longer than the limit → 400. |
| K4 | `logRequest` extension | B → A | Two new optional params: `userSubject` (string, ≤ 255 chars) and `toolCalls` (a JSON array string, ≤ 10000 chars, of `{tool, customer, outcome, resultId?}`). `getRequest` returns both. `listRecentRequests` does **not** return them. |
| K5 | MCP service auth | B → A, C | Every MCP request carries `Authorization: Bearer <token>`. Env vars: `MCP_SERVICE_TOKEN` on each server; `TICKETS_MCP_TOKEN` and `REQUEST_LOG_MCP_TOKEN` on the assistant. A blank token disables the check, which is allowed only in the `local` profile; the `prod` profile requires a token. A request with an `Origin` header not listed in `MCP_ALLOWED_ORIGINS` (empty by default) gets 403. |
| K6 | MCP transport | B → A, C | Streamable HTTP at `/mcp` on 8081 (tickets) and 8082 (request log). SSE `/sse` is removed in the same release, so B10 and A15 merge together (sync point S2). |
| K7 | Ports & health | A, B → C | Assistant: app on 8080, management on 9090, serving `/actuator/health/liveness`, `/actuator/health/readiness` and `/actuator/prometheus`. MCP servers: app and actuator share 8081/8082 and serve the same three endpoints; NetworkPolicy keeps them internal. |
| K8 | Env vars & profiles | A, B → C | Profiles: `local` (H2, no auth, demo seed) and `prod` (fail-fast). New env vars:<br>• OIDC: `OIDC_ISSUER_URI`, `OIDC_AUDIENCE`<br>• Tickets DB: `TICKETS_DB_URL`, `TICKETS_DB_USER`, `TICKETS_DB_PASSWORD`, `TICKETS_DB_MIGRATION_USER`, `TICKETS_DB_MIGRATION_PASSWORD`<br>• Request-log DB migrations: `REQUEST_LOG_DB_MIGRATION_USER`, `REQUEST_LOG_DB_MIGRATION_PASSWORD`<br>• Retention: `REQUEST_LOG_RETENTION` (ISO-8601, default `P90D`)<br>• Tracing: `OTEL_EXPORTER_OTLP_ENDPOINT`, `TRACING_SAMPLING_PROBABILITY`<br>• Embedding model: `EMBEDDING_MODEL_URI`, `EMBEDDING_TOKENIZER_URI`<br>• Rate limit: `RATE_LIMIT_ASKS_PER_MINUTE` |
| K9 | Metric names | A, B → A (alerts) | `helpdesk_ask_seconds{outcome}`, `helpdesk_tool_calls_total{tool,outcome}`, `helpdesk_tool_denied_total{tool}`, `helpdesk_ask_rejected_total{reason}`, `helpdesk_requestlog_writes_total{outcome}`, `requestlog_purged_rows_total`, plus the standard `http_server_requests_seconds` and `hikaricp_*`. |

Legend: ⛓ = depends on another stream's task being merged. **[L]** = large task (counts as 2 toward the session limit).

### Stream A: Assistant & security
- [ ] **A1** (T0.1, assistant side) Add the `helpdesk.max-catalog-question-length` property (default 1000) and check it in `QuestionController`. Map request-log error results caused by validation to 400 instead of 503.
- [ ] **A2** (T0.5, T0.7) Make the queue wait and `Retry-After` configurable. Accept a valid incoming `X-Request-Id`.
- [ ] **A3** (T0.6) Bounded request-log executor and the `helpdesk_requestlog_writes_total{outcome}` metric.
- [ ] **A4** (T0.4) Caffeine cache for `listCustomers`.
- [ ] **A5** (T1.1) Spring Security resource server: deny by default, security headers. The API key remains only in the `local` profile.
- [ ] **A6** (T1.2) Map the JWT to a principal (roles, allowed customers) per K1.
- [ ] **A7** (T1.3) Endpoint authorization per K3.
- [ ] **A8** **[L]** (T1.4) `CustomerScopedToolCallback` using the ToolContext. Hide `listCustomers` from the model and stop sending the customer as user text.
- [ ] **A9** (T1.5) Filter tools per request, so `createTicket` is offered only to users with `tickets-write`. Collect tool-call audit records.
- [ ] **A10** (T1.5, T1.6, client side) Send `userSubject` and `toolCalls` per K4. ⛓ B3.
- [ ] **A11** (T1.8) Per-user rate limit with Bucket4j (`RATE_LIMIT_ASKS_PER_MINUTE`).
- [ ] **A12** (T1.9) Auth/authz test matrix (endpoint × role × customer) and a cross-customer integration test with a fake model.
- [ ] **A13** (T3.3) Health indicators `ticketsMcp`, `requestLogMcp` and `ollama`; readiness group.
- [ ] **A14** (T3.4, client side) Send the service tokens per K5. Harmless before B9 merges.
- [ ] **A15** **[L]** (T3.1 client side + T3.2) Streamable HTTP connections, lazy MCP initialization and reconnect, with a test that restarts a server. ⛓ merges together with B10 (S2).
- [ ] **A16** (T3.5, config side) Make the embedding model and tokenizer URIs configurable (`EMBEDDING_MODEL_URI`, `EMBEDDING_TOKENIZER_URI`).
- [ ] **A17** (T4.1, T4.2, T4.4) For the assistant: JSON logs in `prod`, OTel tracing, and business metrics per K9.
- [ ] **A18** (T5.8) `application-prod.properties` and fail-fast validation of the required properties.
- [ ] **A19** (T4.5) PrometheusRules and a Grafana dashboard in `deploy/observability/`. ⛓ B11 for the MCP panels.
- [ ] **A20** (T6.6) LLM eval set `eval/questions.yaml`, a runner script and `eval/README.md`. The nightly workflow is added by C13.

### Stream B: MCP servers & data
- [ ] **B1** (T2.2) Remove the H2 default from the request-log server; add `local` and `prod` profiles.
- [ ] **B2** (T2.5) Testcontainers Postgres tests for the request-log repositories.
- [ ] **B3** (T1.5, T1.6, server side) Migration `V3__user_subject_and_tool_calls.sql`, plus the `logRequest`/`getRequest` changes and tests per K4.
- [ ] **B4** (T2.3) Separate Flyway (DDL) and app (DML) database users for the request log.
- [ ] **B5** (T2.4) Retention purge job with ShedLock and the `requestlog_purged_rows_total` metric.
- [ ] **B6** (T2.6) Hikari and statement timeouts for the request log.
- [ ] **B7** **[L]** (T2.1) Postgres-backed `TicketTools`: Flyway schema, sequence-based ids, demo seed only in `local`. Add Testcontainers tests; the contract tests must still pass. ⛓ C8 for compose (until then, use Testcontainers).
- [ ] **B8** (T2.3, T2.6) Least-privilege DB users and timeouts for the ticket server.
- [ ] **B9** (T3.4, server side) Service-token filter and Origin check per K5 on both Java servers and the Python server (and Rust, if it is kept).
- [ ] **B10** **[L]** (T3.1, server side) Streamable HTTP on the Java and Python servers. Rust stays on SSE (D2). Make the contract tests transport-selectable, update the `contract` CI job (Rust runs with `sse`) and `.mcp.json`, and write `ticket-mcp-server-rust/README.md` documenting its legacy status. ⛓ merges together with A15 (S2).
- [ ] **B11** (T4.1, T4.2, T4.3) For the MCP servers: JSON logs in `prod`, tracing, and `/actuator/prometheus`.
- [ ] **B12** (T5.8) `prod` profiles with fail-fast validation on both Java servers.
- [ ] **B13** (T6.4) k6 load-test script `perf/ask.js` and a results template. It runs against staging after C11c.

### Stream C: Platform & UI
- [ ] **C1** (T0.2) Fix the jar glob in the `Dockerfile` so release versions build.
- [ ] **C2** (T0.3) Show answers in the UI (per-question result list).
- [ ] **C3** (T6.1) ESLint, Vitest and Testing Library; tests for the answer display and error states; a `ui` CI job.
- [ ] **C4** (T6.3) CI hardening: `permissions: contents: read`, actions pinned by SHA, `renovate.json`.
- [ ] **C5** (T4.6, T6.7 prep) `build-info` and git-commit-id in the root `pom.xml`; `${revision}` versioning.
- [ ] **C6** (T1.10) Keycloak in compose, with `deploy/keycloak/realm-helpdesk.json` per K1 (users `acme-agent`, `globex-agent`, `acme-writer`, `admin`).
- [ ] **C7** **[L]** (T1.7) oauth2-proxy in compose, nginx forwarding `Authorization` per K2, UI redirect to sign-in on 401, user menu and logout, API-key wording removed. ⛓ the end-to-end check needs A5–A7; the UI part can be built against K2/K3 first.
- [ ] **C8** (T5.9) Compose: pin image tags and digests (Ollama, Postgres, nginx, Keycloak), add a tickets database and the env vars from K5/K8. Label the `rust` profile as legacy (D2).
- [ ] **C9** (T6.2) Image build and push to GHCR, Trivy scan, CycloneDX SBOM, cosign signing.
- [ ] **C10** (T3.5, image side) Bake the ONNX model and tokenizer into the assistant image at the paths from K8. ⛓ A16.
- [ ] **C11a** **[L]** (T5.1, T5.2, T5.3) Helm chart skeleton covering all services, probes per K7, resources, PDB/HPA, values for staging and prod, and `helm lint` plus `kubeconform` in CI.
- [ ] **C11b** **[L]** (T5.4–T5.7) Security contexts, ServiceAccounts, NetworkPolicies, Ingress with TLS and security headers, External Secrets.
- [ ] **C11c** **[L]** (T3.6, T3.7, T2.7) Ollama StatefulSet with a GPU node pool, model-pull Job and PVC; KB ConfigMap with a checksum roll; Postgres via CloudNativePG or a managed service, with backups.
- [ ] **C12** (T6.5) E2E smoke job: compose, a tiny Ollama model and a Keycloak test user. ⛓ A7, B7, C7.
- [ ] **C13** (T6.7) Release workflow (tags → images → chart; staging automatic, prod with manual approval) and the nightly eval workflow for A20.
- [ ] **C14** (T7.1, T7.2) `docs/RUNBOOK.md` and `docs/SECURITY.md`.
- [ ] **C15** (T7.3) Final README and `HOW_IT_WORKS.md` update, folding in the "README needs" notes from stream A and B PRs. This is the last task overall.

### Sync points
1. **S1, auth end-to-end:** after A5–A8, C6 and C7. In compose, `acme-agent` can ask about `acme` and gets 403 for `globex`. Each stream checks this before starting its Phase 3 tasks (A13+, B9+, C11a+).
2. **S2, transport switch:** B10 and A15 are developed on their own branches. Merge B10 (contract suite green), then A15 immediately after. MCP is broken on `main` between the two merges.
3. **S3, release candidate (M1):** all P0 tasks are done; C12 is green; C11a–c are deployed to staging; the B13 load test and A20 eval have run.

### Decisions (user-confirmed 2026-11-19)
- **D1, ticket storage (B7):** tickets are stored in **our own Postgres**, in the `tickets` schema/database. There is no external ticketing-system adapter.
- **D2, Rust ticket server (B10):** `ticket-mcp-server-rust` **stays on SSE as a legacy reference implementation**. It is not migrated to Streamable HTTP and is not deployed to production. Consequences:
  - B10 makes the contract tests transport-selectable (`TICKETS_MCP_TRANSPORT=sse|streamable`, default `streamable`). The CI `contract` matrix runs Java and Python with `streamable` and Rust with `sse`, so Rust stays covered as a reference.
  - B10 documents the status in a new `ticket-mcp-server-rust/README.md` (legacy SSE reference, not for production, works only with SSE clients such as `.mcp.json` entries using `type: sse`) and in `ticket-mcp-contract-tests/README.md`.
  - C8 keeps the `rust` compose profile, labelled legacy. After S2 the assistant cannot talk to it, so the profile comment and README must say it is for running the contract tests and MCP Inspector only.
  - C11a: there is no Helm template for the Rust server.
  - C15: the README "Ticket server implementations" table marks Rust as **legacy (SSE, reference only)**.
- **D3, pushing:** agents never push or open PRs. The user does all remote operations (see `CLAUDE.md`).
