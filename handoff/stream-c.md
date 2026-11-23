# Stream c handoff – 2026-11-23
## Done this session
- C5 – `prod/c5-build-info-revision` (base `main`)
- C6 – `prod/c6-keycloak-compose` (base `main`; independent of C5, no shared files)

## Ready to push
### `prod/c5-build-info-revision` (base: `main`)
**PR title:** Add build-info and git commit info to /actuator/info; prepare `${revision}` versioning (C5 / T4.6, T6.7 prep)

**PR body:**
Build info (T4.6) and the groundwork for tag-driven versions (T6.7). Root `pom.xml` and `.gitignore` only.

- `spring-boot-maven-plugin` `build-info` goal in the root `pluginManagement`, so every module that declares the Boot plugin writes `META-INF/build-info.properties`. It shows under `build` in `/actuator/info` (already exposed on all three services).
- `git-commit-id-maven-plugin` (version managed by Spring Boot) in the root build: `git.properties` under `git` in `/actuator/info`.
  - Only `git.branch`, `git.commit.id` (+ abbrev, 12 chars), `git.commit.time`, `git.dirty`, `git.tags` and `git.build.version` are written. No user names, e-mails or host names end up in the jar.
  - `failOnNoGitDirectory=false` and `failOnUnableToExtractRepoInfo=false`, `offline=true`: the Docker build (`.dockerignore` excludes `.git`) and shallow CI clones still build. In images the `git` section is therefore empty for now; the commit belongs in OCI labels / a build arg with C9.
- `revision` property (default `0.0.1-SNAPSHOT`) and `flatten-maven-plugin` 1.10.0 (`resolveCiFriendliesOnly`, `updatePomFile`) so installed/deployed poms carry a resolved version. `.flattened-pom.xml` is git-ignored.
- **The root `<version>` stays literal for now.** Maven cannot resolve a module whose `<parent><version>` is literal against a root whose version is `${revision}` (and the reverse fails too: verified both ways), so the switch must change the root and the three module poms in one commit. The module poms belong to streams A and B. See "Needs from" below. Verified in a scratch copy that with all four changed, `./mvnw package` gives `*-0.0.1-SNAPSHOT.jar` and `-Drevision=1.2.3` gives `*-1.2.3.jar`.

Verified locally:
- `./mvnw verify` passes (tests, coverage gate, format check).
- `java -jar ticket-mcp-server-java/target/*.jar` → `/actuator/info` returns `git` (branch, commit id, time) and `build` (artifact, name, group, version, time).
- `docker build --build-arg MODULE=ticket-mcp-server-java .` succeeds (no `.git` in the context; the Dockerfile's jar glob accepts any version), and the container's `/actuator/info` returns the `build` section.

Note: in a linked git worktree the plugin (JGit) reports the main checkout's HEAD instead of the worktree's. Normal clones, CI and Docker builds are not affected; `dotGitDirectory` and native git did not change this.

**Needs from stream A and stream B (one coordinated commit, user approval needed because it touches all three streams' poms):** switch to CI-friendly versions:
- root `pom.xml` (C): `<version>0.0.1-SNAPSHOT</version>` → `<version>${revision}</version>` and drop the "keep equal" comment;
- `assistant/pom.xml` (A), `ticket-mcp-server-java/pom.xml` (B), `request-log-mcp-server/pom.xml` (B): `<parent><version>0.0.1-SNAPSHOT</version>` → `<version>${revision}</version>`.
Suggest doing it as part of C13 (release workflow) or as a small main-session commit, since it cannot be split per stream.

Plan: ticks C5 (the `${revision}` switch itself follows as described).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### `prod/c6-keycloak-compose` (base: `main`)
**PR title:** Add Keycloak with a local `helpdesk` realm to compose (C6 / T1.10)

**PR body:**
Local identity provider producing the K1 tokens. Nothing consumes the tokens yet; A5–A7 (assistant) and C7 (oauth2-proxy) build on it.

- `keycloak` service in `docker-compose.yml`: `quay.io/keycloak/keycloak:26.8.2` (tag pinned; the digest follows with C8), `start-dev --import-realm`, published on `127.0.0.1:8180` only, health check on the management port 9000 (`/health/ready`, via bash `/dev/tcp`, the image has no curl).
- `deploy/keycloak/realm-helpdesk.json`, realm `helpdesk`, per K1:
  - Access token: `aud=helpdesk-assistant` (audience mapper), `roles` (realm roles, multivalued), `customers` (multivalued user attribute, declared in the declarative user profile, admin-only), `sub`, `preferred_username`. Both custom claims are also in the ID token and userinfo (for `/oauth2/userinfo`, K2).
  - Clients use `fullScopeAllowed=false` with scope mappings for the three roles, so `roles` contains only `helpdesk-agent`, `helpdesk-admin`, `tickets-write` (no `default-roles-helpdesk`, `offline_access` or account roles).
  - Users: `acme-agent` (agent, `acme`), `globex-agent` (agent, `globex`), `acme-writer` (agent + `tickets-write`, `acme`), `admin` (agent + admin + `tickets-write`, `*`). Password `local-only-password`.
  - Clients: `helpdesk-ui` (confidential, auth code + PKCE S256, redirect `http://localhost:5173/oauth2/callback`, secret from `HELPDESK_UI_CLIENT_SECRET`, default `local-only-client-secret`) for oauth2-proxy in C7; `helpdesk-dev-cli` (public, password grant only) for curl and the C12 smoke test; `helpdesk-assistant` (bearer-only, the audience).
  - Brute-force protection on, registration and password reset off.
- Issuer: `KC_HOSTNAME=http://localhost:8180` + `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`. The issuer is always `http://localhost:8180/realms/helpdesk` (browser and containers agree), while a container calling `http://keycloak:8080` gets `http://keycloak:8080/...` for the JWKS and token endpoints.
- Local-only marking: realm display name, client descriptions, compose comment and a new README section "Local sign-in (Keycloak)" with users, clients, the admin console and a curl example. The Keycloak admin console password is `KEYCLOAK_ADMIN_PASSWORD` (default `local-only-admin`).
- Deviation from T1.10's "`dev` profile": Keycloak runs without a compose profile. Compose is local-only anyway, `COMPOSE_PROFILES` already selects the ticket server, and after C7 the default stack needs Keycloak for sign-in.

Verified locally (project `-p c6test`, containers and volumes removed afterwards):
- `docker compose config` succeeds (with and without `COMPOSE_PROFILES=java`).
- Keycloak becomes healthy; log: `Realm 'helpdesk' imported`.
- Password-grant tokens for all four users decode to the expected claims, e.g. `acme-writer`: `iss=http://localhost:8180/realms/helpdesk`, `aud=helpdesk-assistant`, `roles=[tickets-write, helpdesk-agent]`, `customers=[acme]`, `preferred_username=acme-writer`; `admin`: `customers=["*"]`.
- Wrong password → 400; password grant on `helpdesk-ui` → `unauthorized_client`.
- Discovery from inside the compose network (`http://keycloak:8080/realms/helpdesk/.well-known/openid-configuration`): issuer `http://localhost:8180/realms/helpdesk`, `jwks_uri` `http://keycloak:8080/realms/helpdesk/protocol/openid-connect/certs`.

**Needs from stream A** (A5/A6, `assistant/**`): because the issuer (`localhost:8180`) differs from the in-network URL (`keycloak:8080`), Spring's `issuer-uri` discovery from the assistant container would fail its issuer check. In compose, configure the resource server with `jwk-set-uri=http://keycloak:8080/realms/helpdesk/protocol/openid-connect/certs` plus an issuer validator for `OIDC_ISSUER_URI=http://localhost:8180/realms/helpdesk` (an optional `OIDC_JWK_SET_URI` env var would be a backwards-compatible addition to K8). `aud` arrives as a single string when there is only one audience. C7/C8 will set the env vars in compose.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
C7 [L] – dependencies satisfied: partly. The UI part (401 → sign-in, user menu, logout, oauth2-proxy and nginx per K2) can be built against K2/K3 now and on top of C6 (branch from `prod/c6-keycloak-compose` if it is not merged yet, since both edit `docker-compose.yml`); the end-to-end check needs A5–A7. It is [L], so it is the only task of its session.

## Blocked on
nothing (C7's end-to-end check waits for A5–A7)

## Notes for the next session
- `${revision}`: the switch needs one commit across root + 3 module poms (see C5 PR text). Not done; suggest folding into C13 with user approval.
- Keycloak: issuer is fixed to `http://localhost:8180/realms/helpdesk` with dynamic backchannel. oauth2-proxy in C7 runs in a container, so it hits the same issuer mismatch: use `--oidc-issuer-url=http://localhost:8180/realms/helpdesk` with `--skip-oidc-discovery` and explicit `--login-url` (localhost:8180, browser-facing), `--redeem-url` and `--oidc-jwks-url` (keycloak:8080, in-network). Client `helpdesk-ui`, secret env `HELPDESK_UI_CLIENT_SECRET`, redirect `http://localhost:5173/oauth2/callback` (change the realm if C7 uses another URL).
- Realm JSON is generated-looking but hand-maintained; it has no comments (JSON). Keycloak substitutes `${ENV:default}` placeholders on import (used for the client secret).
- Token check script used in C6 (password grant with `helpdesk-dev-cli`, decode the payload): see the curl example in README "Local sign-in (Keycloak)".
- Test Keycloak with `HELPDESK_API_KEY=x REQUEST_LOG_DB_PASSWORD=y docker compose -p <name> up -d keycloak`, then `docker compose -p <name> down -v`. Port 8180 was free; 5173 and 8080 are used by the user's own processes.
- git-commit-id in a linked worktree reports the main checkout's HEAD (JGit); harmless for CI/Docker.
- actionlint and the Renovate validator were still not run (see the C4 notes).
