# Stream c handoff – 2026-11-25
## Done this session
- C12 (T6.5) – `prod/c12-e2e-smoke` (from `main` at 5986b1e)

## Ready to push
### `prod/c12-e2e-smoke` (base: `main`)
**PR title:** E2E smoke CI job: compose stack, tiny Ollama model, Keycloak sign-in through oauth2-proxy (C12 / T6.5)

**PR body:**
Implements C12 / T6.5 of PRODUCTION_READINESS_PLAN.md.

**New `.github/e2e/smoke.sh`** (bash, curl, jq). It drives a running compose stack the way a browser does, through the real K2 path (oauth2-proxy → Keycloak login form → session cookie → nginx → assistant):
1. Without a session, `GET /customers` gets 401 (oauth2-proxy API route).
2. It signs in as `acme-agent` (realm `deploy/keycloak/realm-helpdesk.json`, client `helpdesk-ui`, authorization code + PKCE). It loads `/`, submits Keycloak's login form, and follows the redirects through `/oauth2/callback` back to the SPA. Then `/oauth2/userinfo` must name the user.
3. `GET /customers` must be exactly `["acme"]`.
4. `POST /ask` for `acme` must return 200 with a non-empty answer. It is sent with its own `X-Request-Id`.
5. `GET /requests?customer=acme` must contain the row with that request id, with `customer`, `question` and `status == "SUCCESS"`. The script polls for up to 30 s, because the log is written in the background.
6. The cross-customer checks: `acme-agent` gets 403 on `POST /ask` for `globex` and on `GET /requests?customer=globex`.

Failures print the step, the HTTP status and the start of the body. The user, customers, question and host ports can be overridden by env vars (`E2E_*`). With `E2E_UI_PORT`/`E2E_KEYCLOAK_PORT`, curl connects to other host ports (`--connect-to`) while the URLs stay `localhost:5173`/`localhost:8180`, the only redirect URI and issuer the realm accepts.

**New `.github/workflows/e2e-smoke.yml`**
- Triggers: `pull_request` with a paths filter on the wiring (`docker-compose.yml`, the Dockerfiles, `.dockerignore`, `ui/nginx.conf.template`, `deploy/keycloak/**`, root and module POMs, `*/src/main/resources/application*.properties`, the assistant's `SecurityConfig`/`HelpdeskJwtConverter`, and the test itself), plus `workflow_dispatch` with inputs `profile` (`java` | `python`, default `java`) and `model` (default `qwen2.5:0.5b`).
- Steps: `docker compose build`, then `docker compose up --detach --wait --wait-timeout 900`. That waits for every container to be healthy, and for the `ollama-pull` one-shot to complete. Then it runs the smoke script. On failure it prints `ps --all` and all logs. `down --volumes` always runs.
- Compose project `e2e`, `contents: read` only, 40-minute timeout, concurrency per PR. The only action is `actions/checkout`, pinned by SHA like the other workflows. There are no secrets; only `REQUEST_LOG_DB_PASSWORD` is set (throwaway), and everything else uses compose's local-only defaults, including the K5 service tokens.

**Why a tiny real model rather than a stub Ollama server:** `qwen2.5:0.5b` is about 400 MB, supports tool calling, and answered every local `/ask` in 1 to 12 s on CPU. With it, the job also covers the real Ollama API, the model pull, and Spring AI's chat and tool-calling loop, which a stub would fake. The script asserts only wiring (status codes, a non-empty answer, the history row), never what the model says, so answer quality cannot make the job flaky. If it ever does get flaky, the `model` input or `OLLAMA_MODEL` is the single place to change. The ticket MCP server is exercised in every run by `/customers` and by the customer check in `/ask` (`listCustomers`). Whether the tiny model also calls the ticket tools is not asserted.

**Forward compatibility:** compose already sets the K5 tokens on both sides (C8), so the job keeps working after B9 + A14 merge (as long as A14 merges no later than B9, as C8 notes). After B10/A15 (Streamable HTTP), nothing here changes either. The `rust` profile is not offered, since it is legacy (D2).

**README:** a short "E2E smoke test" paragraph under "Local sign-in (Keycloak)" on how to run it locally and in Actions.

**Verified locally** with a separate compose project `c12e2e` on host ports 15173/18080/18180 (port override file outside the repo; the user's `helpdesk-assistant-*` stack untouched), using the same steps as the job:
- `java` profile: `up --wait` became healthy in about 5 minutes, including the image pulls and the model pull. The smoke test passed (about 10 s). It also passed 3 more times with a ticket question ("Which open tickets does acme have?"), and once as `globex-agent` with the customers swapped.
- `python` profile: `up --build --wait` was healthy and the smoke test passed.
- Negative check: a wrong password fails at sign-in with a clear message (exit 1).
- actionlint 1.7.7: no findings for `e2e-smoke.yml`. shellcheck (stable): no findings for `smoke.sh`.
- Afterwards, all containers, volumes, the network, the built images and the images pulled for the test were removed.

**Not verified:** a run on GitHub's runners. Expect roughly 10 to 15 minutes there: there is no layer cache, so the three Java images and the UI image are built from scratch.

**Needs from other streams:** none.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
C10 – dependencies satisfied: no (⛓ A16). C11a [L] – no (waits for S1 / A8 per the plan; check whether the plan considers S1 reached).

## Blocked on
C10 waits for A16; C11a+ wait for S1.

## Notes for the next session
- C9 is merged; the old `prod/c9-image-pipeline-restack` notes no longer apply.
- Local isolated compose runs: put a port override (`ports: !override [...]`, compose v5 supports it) in the scratchpad and call `docker compose --project-directory <worktree> -f <worktree>/docker-compose.yml -f <override> ...` with `COMPOSE_PROJECT_NAME=c12e2e` (or similar). Then run `E2E_UI_PORT=15173 E2E_KEYCLOAK_PORT=18180 bash .github/e2e/smoke.sh`.
- `docker compose up --wait` handles the `ollama-pull` one-shot fine (it reports "Exited" and the command still exits with 0).
- The assistant does not log successful tool calls, so the logs cannot show whether the model used the ticket tools.
- Possible follow-up: cache the image builds in the E2E job (e.g. the `images.yml` GHA cache scopes via `docker buildx bake` with compose), if the job is too slow.
- The worktree-isolation guard rejects commands that combine `git` with more complex shell (pipes, `${PIPESTATUS}`, etc.). Keep git commands plain.
- actionlint: `docker run --rm -v "<worktree>:/repo" -w /repo rhysd/actionlint:1.7.7`; shellcheck: `docker run --rm -v "<dir>:/mnt:ro" koalaman/shellcheck:stable /mnt/<file>`. Remove both images afterwards.
- The user's stack (`helpdesk-assistant-*`) runs on 5173/8080/8081/8082.
