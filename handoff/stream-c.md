# Stream c handoff – 2026-11-23
## Done this session
- C3 – `prod/c3-ui-lint-tests` (base `main`)
- C4 – `prod/c4-ci-hardening` (base `prod/c3-ui-lint-tests`, stacked; both edit `.github/workflows/ci.yml`)

## Ready to push
### `prod/c3-ui-lint-tests` (base: `main`)
**PR title:** Add ESLint, Vitest and Testing Library to the UI, plus a `ui` CI job (C3 / T6.1)

**PR body:**
The UI had no linting and no tests (finding F1).

- ESLint 10 flat config (`ui/eslint.config.js`): `@eslint/js` recommended, typescript-eslint, react-hooks (v7, incl. the React Compiler rules) and react-refresh. `npm run lint` fails on any warning.
- Vitest with jsdom and Testing Library (`ui/vitest.config.ts`, `ui/src/test/`), `npm test`. 14 tests:
  - `AnswerList`: empty state; answered, failed (error in an alert), asking (`aria-busy`) and waiting states; multi-line answers.
  - `api`: request body, query encoding, problem-detail error message, non-JSON error body, the 401 message.
  - `App` with a mocked `fetch`: ask → answer shown → history re-read; one failed and one answered question in a batch with the summary message; 401 message; load errors for customers, questions and history; history follows the selected customer.
- Fixed the two react-hooks findings in `App.tsx` without changing behaviour: the customer ref is updated in an effect instead of during render, and the history state is tagged with its customer instead of being reset inside an effect.
- New CI job `ui`: `npm ci && npm run lint && npm test && npm run build` (Node 22).
- `CLAUDE.md` and `README.md`: UI lint/test commands.

The "401 redirect" test from T6.1 belongs with C7 (sign-in redirect); today a 401 shows an explanatory message, and that is what is tested.

Verified locally: `cd ui && npm ci && npm run lint && npm test && npm run build`, all green.

Plan: ticks C3.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### `prod/c4-ci-hardening` (base: `prod/c3-ui-lint-tests`; merge C3 first, then retarget to `main`)
**PR title:** Harden CI: least-privilege token, SHA-pinned actions, one run per commit, Renovate, resilient Maven downloads (C4 / T6.3)

**PR body:**
CI hardening (finding E5) plus the two CI items added to C4.

- `permissions: contents: read` at the workflow level.
- `on: {push: {branches: [main]}, pull_request: {}}`: a push to a PR branch now runs CI once instead of twice. A `concurrency` group cancels superseded runs of the same PR (runs on `main` always finish).
- Actions in the `java`, `ui`, `python` and `rust` jobs are pinned by commit SHA, with the tag in a comment, and moved to their current node24 majors: `actions/checkout` v7.0.1, `actions/setup-java` v6.3.0, `actions/setup-node` v7.1.0, `astral-sh/setup-uv` v10.4.0, `Swatinem/rust-cache` v2.9.3. Checkouts use `persist-credentials: false`.
- `renovate.json`: `config:recommended` + `helpers:pinGitHubActionDigestsToSemver`, weekly schedule, monthly lock-file maintenance, grouped non-major PRs per ecosystem (Maven + wrapper, npm, Python/uv, Cargo, Docker/compose) and one PR for GitHub Actions. The Renovate GitHub app must be installed on the repo for this to take effect.
- Maven HTTP retries in `.mvn/maven.config` (applies to local builds, CI and the Dockerfile): `aether.connector.http.retryHandler.count=5`, `requestSentEnabled=true`, and retries on HTTP `429,500,502,503,504` (default is only 429/503). Property names checked against the resolver 1.9.27 bundled with Maven 3.9.16. This addresses the flaky `contract (java)` image build in CI run 44553395898.
- `Dockerfile`: `dependency:go-offline` resolves into a BuildKit cache mount (`/m2cache`, `sharing=locked` so parallel image builds don't write the same repository), and the repository is then copied into the layer.
  - Deviation from the plan text, on purpose: there is no cache mount on the `package` step. CI's `contract` job restores *layers* from the `type=gha` cache but not cache mounts. With a mount on both steps, a cached go-offline layer would leave `package` with an empty repository and re-download everything on every run, which means more Central traffic, not less. This way local rebuilds after a pom change reuse the mount, and CI still gets the dependencies from the cached layer.
- `Dockerfile`: the Maven wrapper's download of Maven itself (`apache-maven-3.9.16-bin.zip`) is retried up to 5 times with backoff, in its own layer before the dependency step. The wrapper has no retry of its own and `.mvn/maven.config` only applies once Maven runs; this download failed the `contract (java)` job in CI run 45158269288. In CI the layer comes from the layer cache, so the download only happens when `.mvn/` or `mvnw` changes.

Stacked on C3 because both edit `.github/workflows/ci.yml`.

The `contract` job is owned by stream B and is unchanged, so it still uses floating tags on the deprecated Node 20 majors (see below).

**Needs from stream B** (the `contract` job in `.github/workflows/ci.yml`): pin its actions by SHA on the current majors, with `persist-credentials: false` on checkout:
- `actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1`
- `docker/setup-buildx-action@f87e5991a6d7451dcb8d9637bfbc97413f497069 # v4.4.1`
- `docker/build-push-action@c3c9e263c25d99ce0380d002d59b67737d91b0dc # v7.4.0`
- `astral-sh/setup-uv@1db35dd8eee11c5894872e0b383ae6c406cf738d # v10.4.0`
(Renovate would also propose these once installed.)

Verified locally:
- `./mvnw verify` passes with the new `.mvn/maven.config`.
- `docker build --build-arg MODULE=ticket-mcp-server-java .` succeeds. The go-offline step ran with the cache mount, and `package` then built offline from the copied repository in about 5 s.
- The workflow parses (PyYAML); the `contract` job is byte-identical to `main`; every pinned SHA was resolved from its release tag, and its `action.yml` declares `node24` and the inputs we pass. actionlint and the Renovate config validator were **not** run (tool install was not permitted here). Worth a run before merging, or check the first CI run.

Plan: ticks C4. Also updates `handoff/stream-c.md`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
C5 – dependencies satisfied: yes (root `pom.xml` build-info / git-commit-id / `${revision}`). Base it on `main` once C3 and C4 are merged; otherwise it touches no file of C3/C4 except possibly `.mvn/`, so `main` is fine.

## Blocked on
nothing

## Notes for the next session
- actionlint and the Renovate config validator could not be run: installing them (Docker image, Homebrew, npx renovate) was denied by the permission classifier. The workflow was checked by parsing it with PyYAML (`uv run --no-project --with pyyaml ...`) and by checking each pinned action's `action.yml` inputs and runtime via `gh api`. Run `actionlint` and `npx --package renovate -- renovate-config-validator renovate.json` if they become available.
- UI tooling versions: ESLint 10, typescript-eslint 8, eslint-plugin-react-hooks 7 (React Compiler rules such as `set-state-in-effect` and `refs` are errors), Vitest 5 (needs Vite ≥ 6.4 and Node ≥ 22.12), jsdom 31 (Node ≥ 22.22).
- Test helpers: `ui/src/test/fetchMock.ts` (`mockFetch({'GET /path': (body, url) => json(...)})`), and `setup.ts` stubs `window.matchMedia` for ThemeToggle.
- C7 should add a test for the 401 → sign-in redirect and change the 401 test in `App.test.tsx` and `api.test.ts`.
- Ports 5173 and 8080 were in use by the user's own processes earlier; use other ports for local dev servers.
