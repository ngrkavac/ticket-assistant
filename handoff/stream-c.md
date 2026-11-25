# Stream c handoff – 2026-11-25
## Done this session
- C7 [L] – `prod/c7-oauth2-proxy-sign-in` (base `main` d704c75)

## Ready to push
### `prod/c7-oauth2-proxy-sign-in` (base: `main`)
**PR title:** Sign in through oauth2-proxy and forward the access token to the assistant (C7 / T1.7)

**PR body:**
Implements K2 in compose and the UI side of K3. Browser → oauth2-proxy (cookie session) → nginx → assistant with `Authorization: Bearer <access token>`. The browser never sees a token.

**Compose (`docker-compose.yml`)**
- New `oauth2-proxy` service (`quay.io/oauth2-proxy/oauth2-proxy:v7.15.6`, tag pinned; digest follows with C8) on `127.0.0.1:5173`. It is the only way to the UI.
  - Provider `oidc`, client `helpdesk-ui`, authorization code + PKCE S256, redirect `http://localhost:5173/oauth2/callback` (as registered in the C6 realm).
  - Keycloak's issuer is `http://localhost:8180/realms/helpdesk`, which a container cannot reach, so discovery is skipped. The login URL is `localhost:8180` (browser), while the token, JWKS, userinfo and logout calls go to `keycloak:8080`.
  - `--pass-access-token`: the access token (`aud=helpdesk-assistant`) goes upstream in `X-Forwarded-Access-Token`. We don't use `--pass-authorization-header`, because that sends the ID token, whose audience is `helpdesk-ui`.
  - `--api-route ^/(ask|requests|customers|questions)$`: API calls without a session get 401 instead of a redirect. `--skip-provider-button`: page loads go straight to the Keycloak login.
  - `--cookie-refresh=4m` (Keycloak access tokens live 5 minutes) and `--cookie-expire=10h` (the realm's SSO max).
  - `--backend-logout-url=http://keycloak:8080/.../logout?id_token_hint={id_token}`: sign-out also ends the Keycloak session.
  - `--oidc-groups-claim=roles`, so `/oauth2/userinfo` returns the K1 roles as `groups`.
  - Local-only defaults: `HELPDESK_UI_CLIENT_SECRET` (shared with the realm) and `OAUTH2_PROXY_COOKIE_SECRET` (`local-only-cookie-secret-32bytes`).
- `ui` is no longer published, because nginx trusts the token header it gets from oauth2-proxy. It has no `HELPDESK_API_KEY` any more.
- `assistant` drops the `local` profile and `HELPDESK_API_KEY`. It now runs with `OIDC_ISSUER_URI=http://localhost:8180/realms/helpdesk`, `OIDC_JWK_SET_URI=http://keycloak:8080/realms/helpdesk/protocol/openid-connect/certs` and `OIDC_AUDIENCE=helpdesk-assistant` (A5's "Needs from stream C"). Its port is now bound to `127.0.0.1:8080` only.
- `HELPDESK_API_KEY` is no longer required to run compose.

**nginx (`ui/nginx.conf.template`, `ui/Dockerfile`)**
- `Authorization: Bearer $http_x_forwarded_access_token` via a `map`. The header is always overwritten, so a browser-sent `Authorization` never passes; with no token, nginx drops it.
- Strips `X-Forwarded-Access-Token`, `X-Forwarded-User/Email/Preferred-Username/Groups`, `Cookie` and `X-API-Key` before calling the assistant.
- `NGINX_ENVSUBST_FILTER` now covers `ASSISTANT_URL` only.

**UI**
- `api.ts`: on a 401 the browser goes to `/oauth2/sign_in?rd=<current page>`. A once-per-minute guard in `sessionStorage` stops a redirect loop when the assistant rejects a fresh session. The "HELPDESK_API_KEY is not set" text is gone. New `getUser()` reads `/oauth2/userinfo`; it returns null without oauth2-proxy (the Vite dev server).
- New `UserMenu` in the header: name, e-mail, roles (Agent / Admin / Can create tickets) and **Sign out** (`/oauth2/sign_out`). It is a `<details>` disclosure that closes on Escape or an outside click, and on phones it shows only the avatar.
- The "New question" form is disabled with "Only admins can add questions." for signed-in users without `helpdesk-admin` (K3). Without user info (dev server) nothing is restricted.
- `vite.config.ts`: dev proxy unchanged (optional `X-API-Key` for an assistant in the `local` profile); comment reworded.
- Tests: 401 redirect and loop guard, userinfo mapping and fallbacks, user menu (contents, Escape, outside click), admin-only form, dev server without oauth2-proxy. `npm run lint && npm test && npm run build` pass (24 tests).

**Docs:** README ("Web UI" request path and sign-in, compose quick start without the API key, token curl example, Auth/Config rows with the `OIDC_*` env vars from A5/A6, `SPRING_PROFILES_ACTIVE=local` for the Maven quick start) and HOW_IT_WORKS (token curl, 401 troubleshooting).

**Verified locally** (compose project `c7test`, oauth2-proxy on `:15173`, containers, volumes and images removed afterwards):
- Stub assistant that echoes headers:
  - Without a session, `/customers` and `/oauth2/userinfo` → 401, and `/` → 302 to Keycloak with `code_challenge_method=S256`.
  - Login as `acme-agent` and `admin` → callback → back to `/?x=1`. Userinfo e.g. `{"preferredUsername":"admin","groups":["helpdesk-admin","tickets-write","helpdesk-agent"],...}`.
  - The assistant receives only `Authorization: Bearer <access token>`: `iss=http://localhost:8180/realms/helpdesk`, `aud=helpdesk-assistant`, `azp=helpdesk-ui`, `roles`, `customers`. A forged `Authorization`, `X-Forwarded-Access-Token` and `X-API-Key` from the client were dropped or replaced, and no cookies or user headers reach it.
  - Sign-out → 302 `/`, then the API → 401 and Keycloak shows the login form again (the SSO session has ended).
  - Token refresh: after 320 s (past the 5-minute lifetime) `/customers` still → 200 with a new access token.
- Real assistant built from this branch (Ollama not started): after sign-in through oauth2-proxy, `/customers`, `/questions` and `/requests` → 200, and after sign-out → 401. So the assistant's `OIDC_JWK_SET_URI` + issuer validation accepts the Keycloak tokens forwarded by nginx.
- Not verified: asking a question end-to-end (needs the llama3.1 pull), and S1's 403 for `globex` (needs A7/A8).

**Needs from stream A:** none blocking. With A7 merged, a 403 (e.g. `acme-agent` asking for `globex`) shows the assistant's problem detail in the answer list. No special UI handling is needed. `/customers` already drives the picker, so it narrows automatically once A7 filters it.

Plan: ticks C7. The S1 end-to-end check runs after A7/A8.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
C8 – dependencies satisfied: yes (pin image tags and digests, including the new oauth2-proxy image; tickets database and K5/K8 env vars; label the `rust` profile legacy). It edits `docker-compose.yml` like C7, so branch from `prod/c7-oauth2-proxy-sign-in` if C7 is not merged yet.

## Blocked on
nothing (the S1 auth end-to-end check waits for A7/A8)

## Notes for the next session
- oauth2-proxy has a distroless image with no shell, so it has no compose healthcheck. Nothing depends on it.
- Testing the sign-in without a browser: the C7 test script lived in the session scratchpad and was not committed. Its approach: Python `urllib` with a cookie jar and redirects off; set `cookie.secure = False` after each response (Keycloak sets `Secure` cookies, browsers treat http://localhost as secure but cookiejar does not); parse the login form `action`; POST `username`/`password`; rewrite the callback URL from `localhost:5173` to the test port. An override file swapped `assistant` for a `python:3.13-alpine` header-echo stub (`build: !reset null`, `depends_on: !reset {}`) and moved oauth2-proxy to `127.0.0.1:15173`.
- The user's own stack (`helpdesk-assistant-*`) was running on 5173/8080/8081/8082. Always use `-p <name>` and other ports.
- The realm accepts only the redirect `http://localhost:5173/oauth2/callback` and post-logout `http://localhost:5173/*`. Helm (C11b) needs its own client and redirect URIs, plus `cookie-secure=true` behind TLS and a real cookie secret from External Secrets.
- For K8s (C11a/b), nginx must be reachable only from oauth2-proxy (NetworkPolicy), because it trusts `X-Forwarded-Access-Token`.
- From earlier sessions: the `${revision}` switch needs one commit across the root pom and 3 module poms (see the C5 PR text). actionlint and the Renovate validator have still not been run.
