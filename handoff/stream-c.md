# Stream c handoff – 2026-11-27
## Done this session
- C11a [L] – `prod/c11a-helm-chart` (from `main` at e2a22b6)

## Ready to push
### `prod/c11a-helm-chart` (base: `main`)
**PR title:** Helm chart skeleton for all deployable services, with lint and kubeconform in CI (C11a)

**PR body:**
Stream C, task C11a (T5.1, T5.2, T5.3). No contract change: the chart uses K5–K8 as written.

**What changes**
- New chart `deploy/helm/helpdesk/` with one template per service:
  - `assistant`: Deployment, Service (8080 only), PDB and HPA.
  - `ticket-mcp-server`: Java or Python, chosen by `ticketServer.implementation`, behind one Service name. There is no Rust template (D2), and the chart refuses `rust`.
  - `request-log-mcp-server` and `ui`.
  - `oauth2-proxy`: its own template rather than the upstream dependency chart. It uses the same pinned image as compose, needs no remote chart fetch in CI, and keeps the timeout chain and API routes next to the UI settings they must match. It uses OIDC discovery, a secure cookie and reverse-proxy mode.
- **MCP (K6):** `TICKETS_MCP_URL` and `REQUEST_LOG_MCP_URL` are the Services' base URLs (`http://<release>-ticket-mcp-server:8081`, `...-request-log-mcp-server:8082`). The assistant appends `/mcp` itself (`spring.ai.mcp.client.streamable-http.connections.*.endpoint=/mcp`). A URL ending in `/mcp` would become `/mcp/mcp`. The servers are stateless, so replicas need no session affinity.
- **Probes (K7, T5.2):** `startupProbe`, `readinessProbe` (`/actuator/health/readiness`) and `livenessProbe` (`/actuator/health/liveness`). They run on the assistant's management port 9090 and on 8081/8082 for the MCP servers; the Python server serves the same paths. The assistant's startup allows 36 × 5 s = 3 min (KB indexing and model load). The UI uses `/healthz`. oauth2-proxy uses `/ready` and `/ping`.
- **Resources (T5.3):** the assistant requests 1 CPU / 1.5 Gi with a 2 Gi memory limit. That leaves room above the 75 % heap (`MaxRAMPercentage`, set in the image) for the ONNX and PyTorch natives. The MCP servers get 250m / 512Mi and the UI 50m / 64Mi. There are no CPU limits (they cause throttling). These are starting points to tune after B13.
- **PDB/HPA:** every component has a PDB with `maxUnavailable: 1`. The assistant HPA scales on CPU, with an optional per-pod in-flight metric that needs a custom metrics adapter (off by default). Scale-down is stabilised for 5 min. `templates/validate.yaml` refuses to render when the assistant's maximum pods × `maxConcurrentRequests` exceeds `ollama.capacity.replicas × numParallel` (T3.6). Prod: 4 × 2 = 8 ≤ 2 × 4.
- **`/tmp`:** every app pod gets an `emptyDir` at `/tmp`. Kubernetes does not mount it `noexec`, and onnxruntime maps a native library from there. It also keeps `/tmp` writable once C11b sets `readOnlyRootFilesystem`.
- **Graceful shutdown:** a native `preStop` sleep of 5 s (Kubernetes ≥ 1.30, `kubeVersion` in Chart.yaml), then Spring's 30 s graceful shutdown. The UI (190 s) and oauth2-proxy (200 s) get longer grace periods so a running `/ask` can finish. Pods spread softly across nodes.
- **Env:** `OLLAMA_URL`, `OLLAMA_MODEL` (pinned `llama3.1:8b-instruct-q4_K_M`), `OLLAMA_NUM_PREDICT` (1024), `HELPDESK_MAXCONCURRENTREQUESTS`, the OIDC settings (K1/K2), tracing (K8), and the DB pool and statement timeout. The image already sets `EMBEDDING_MODEL_URI`, `EMBEDDING_TOKENIZER_URI`, `DJL_CACHE_DIR` and `DJL_OFFLINE`, so the chart does not. `SPRING_PROFILES_ACTIVE=prod` (`global.springProfile`).
- **No secrets in values:** the chart creates no Secret. It references existing ones through `secrets.*` (names and keys): the MCP tokens (K5), the tickets and request-log DB credentials, with the migration users optional (`optional: true`), and the oauth2-proxy client id, client secret and cookie secret.
- **Values:** `values.yaml` holds the documented defaults. `values-staging.yaml` runs 1 replica per service, with the assistant HPA at 1–2, P14D retention and 100 % trace sampling. `values-prod.yaml` runs 2 replicas everywhere, with the assistant HPA at 2–4. Host names and DB URLs are `example.com`/in-cluster placeholders. Without an environment file the chart refuses to render (`publicHost`, `oidc.issuerUri` and the DB URLs are `required`).
- **Hooks for later tasks:**
  - C11b: empty `podSecurityContext`/`securityContext` per component, the Secret names, `publicHost`, and the "C11b" comments for the Ingress and NetworkPolicies.
  - C11c: `ollama.url`/`ollama.capacity`, `assistant.kb`, and the `*.db.url` values.
  - Every component also has `extraEnv`, `extraVolumes` and `extraVolumeMounts`.
- **CI:** the new `.github/workflows/helm.yml` runs on changes to `deploy/helm/**` and calls `deploy/helm/check.sh`. That script runs:
  - `helm lint --strict` and `helm template | kubeconform -strict` (Kubernetes 1.30.0 schemas) for staging and prod;
  - four guard cases that must be refused: no env file, Ollama capacity exceeded, `rust`, and Python with 2 replicas;
  - a kubeconform run of the Python variant.
  helm (`alpine/helm:3.17.3`) and kubeconform (`ghcr.io/yannh/kubeconform:v0.7.0`) run from images pinned by tag and digest.
- `renovate.json`: a regex custom manager keeps those two workflow images current (`# renovate: docker` comment). `helm-values` and `custom.regex` join the "Docker images non-major" group; `helm-values` covers the oauth2-proxy image in `values.yaml`.

**Verified locally**
- `deploy/helm/check.sh` with helm 3.17.3 and kubeconform v0.7.0 (both run through Docker, as in CI): lint is clean for both environments; kubeconform finds 16/16 resources valid for staging, prod and the Python variant; all four guard cases are refused with the expected message.
- I read through the rendered manifests: image references (`repo:tag@digest` when a digest is set), Secret refs, probes, the HPA, and the `/tmp` mounts.
- actionlint 1.7.7 reports no findings for `helm.yml`. The Renovate regex matches both images (checked with Python).
- No Java, UI or compose files changed, so `./mvnw verify` and the other CI jobs are unaffected.
- Nothing was deployed to a cluster. The first real install comes with C11b/C11c (secrets, Ingress, Ollama, Postgres).

**Notes**
- `HELPDESK_MAXCONCURRENTREQUESTS` is Spring's canonical env form of `helpdesk.max-concurrent-requests` (a `@ConfigurationProperties` record).
- The assistant has no `application-prod.properties` yet (A18). `SPRING_PROFILES_ACTIVE=prod` is harmless until then.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
C11b [L] – dependencies satisfied: yes, once C11a is merged (branch from `prod/c11a-helm-chart` if it is not merged yet, and say so in the PR text).

## Blocked on
nothing for C11b. C11c can follow C11b. C13 needs C11a (chart) and A20 (nightly eval). C14 is better after C11 and stream A's alerting work (A19).

## Notes for the next session
- **Chart layout (C11a):** `deploy/helm/helpdesk/` (templates per component, `_helpers.tpl`, `validate.yaml`), `deploy/helm/check.sh` (lint, kubeconform and guards), `.github/workflows/helm.yml`. Run the check locally without installing tools:
  `cd deploy/helm && HELM="docker run --rm -i -v $PWD:/w -w /w alpine/helm:3.17.3" KUBECONFORM="docker run --rm -i ghcr.io/yannh/kubeconform:v0.7.0" ./check.sh`
  When you add a template, add a guard case to `check.sh` if it has a `fail`/`required`.
- **C11b:**
  - Line up the timeouts: Ingress ≥ oauth2-proxy `upstreamTimeout` (190s, as in compose) ≥ nginx `proxy_read_timeout` (180s in `ui/nginx.conf.template`). For ingress-nginx that means `proxy-read-timeout`/`proxy-send-timeout` ≥ 190 (use e.g. 200).
  - `/tmp` must stay writable and exec-able. C11a mounts a plain `emptyDir` at `/tmp` on every app pod; do not switch it to a `noexec` mount. onnxruntime maps `libonnxruntime.so` from `/tmp`.
  - With `readOnlyRootFilesystem`, nginx-unprivileged also needs writable `/var/cache/nginx` and `/etc/nginx/conf.d` (the entrypoint renders the template there). See the comment in `templates/ui.yaml`.
  - Fill in the empty `podSecurityContext`/`securityContext` values. Add ServiceAccounts with `automountServiceAccountToken: false`, NetworkPolicies (selectors via `helpdesk.selectorLabels`; the assistant also needs egress to the OIDC issuer for JWKS, to Ollama and OTLP), the Ingress for `publicHost` → `<release>-oauth2-proxy:4180`, and ExternalSecrets that create the Secrets named under `secrets.*` (names and keys are in `values.yaml`). oauth2-proxy already runs with `OAUTH2_PROXY_REVERSE_PROXY=true`.
  - The assistant Service exposes only 8080. Prometheus scraping of 9090 (or 8081/8082) needs a ServiceMonitor/PodMonitor or pod annotations, which is A19's side, plus a NetworkPolicy rule.
- **C11c:**
  - `ollama.capacity.{replicas,numParallel}` are meant to drive the StatefulSet replicas and `OLLAMA_NUM_PARALLEL`, so the capacity guard stays true. Replace `ollama.url` with the in-chart Service.
  - `assistant.kb` is the KB ConfigMap hook (`KB_LOCATION=file:/kb/*.md`, checksum annotation).
  - `ticketServer.java.db.url` and `requestLogServer.db.url` are the Postgres hooks.
- **C15 (README/HOW_IT_WORKS), collected from merged PRs:**
  - `/sse` became `/mcp`: the curl and MCP Inspector examples change to `http://localhost:8081/mcp` and `http://localhost:8082/mcp`.
  - The Rust server is a legacy SSE reference (D2): not deployed, and only usable with SSE clients and the contract tests.
  - Document `OLLAMA_NUM_PREDICT` (default 1024; -1 = no cap).
  - The `Customer:` line, and the model calling `listCustomers` (A8).
  - `createTicket` needs the `tickets-write` role (A9).
  - The assistant starts without the MCP servers (lazy start, reconnects by itself, A15).
  - Also mention the Helm chart (`deploy/helm/helpdesk`, `check.sh`).
- **Optional compose follow-ups:** drop the assistant's `depends_on: request-log-mcp-server` (lazy MCP start, A15). The E2E smoke could set `OLLAMA_NUM_PREDICT=256` for the assistant, so a looping qwen2.5:0.5b ends after about 5 s.
- The worktree guard rejects compound Bash commands (`mkdir && cd && heredoc`) and "github" strings in complex forms. Use the Write/Edit tools for files and keep Bash commands simple.
- The Docker images `alpine/helm:3.17.3`, `ghcr.io/yannh/kubeconform:v0.7.0` and `rhysd/actionlint:1.7.7` are still pulled locally.
