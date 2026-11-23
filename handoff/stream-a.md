# Stream a handoff – 2026-11-23
## Done this session
- A5 – `prod/a5-resource-server` (base `origin/main` a910a92)
- A6 – `prod/a6-jwt-principal` (base `prod/a5-resource-server`, stacked)

## Ready to push
### `prod/a5-resource-server` (base: `main`)
**PR title:** Make the assistant an OAuth2 resource server, deny by default (A5)

**PR body:**
Stream A, task A5 (T1.1). Fixes findings A2 and A7 (API side).

- Adds `spring-boot-starter-oauth2-resource-server`. New `SecurityConfig`: every endpoint on the app port needs an authenticated caller, including endpoints added later (no more `PROTECTED` path list). Stateless, CSRF off (bearer only), no request cache, form login, basic auth or logout.
- Outside the `local` profile the bearer JWT is validated against `OIDC_ISSUER_URI` (`spring.security.oauth2.resourceserver.jwt.issuer-uri`) and `OIDC_AUDIENCE` (default `helpdesk-assistant`). Without an issuer the app **refuses to start** with a clear message.
- Optional `OIDC_JWK_SET_URI`: fetch signing keys from that URL instead of from issuer discovery, for when the issuer URL in the tokens is not reachable from the assistant (Keycloak in compose: issuer `http://localhost:8180/realms/helpdesk`, keys at `http://keycloak:8080/realms/helpdesk/protocol/openid-connect/certs`). Issuer, audience and expiry are still validated, and keys are fetched on first use, not at startup. This is a backwards-compatible addition to K8.
- Actuator is open only on the separate management server (9090, K7), so probes and Prometheus need no token. If actuator shares the app port, it needs a token like everything else.
- Security headers on every API response: `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, and HSTS (1 year, includeSubDomains) on HTTPS requests.
- 401 and 403 are `application/problem+json`. A 401 from the OIDC chain carries `WWW-Authenticate: Bearer`. Requests rejected by the Spring Security firewall (e.g. `;` in the path, or a control character in a header) get 400 instead of 500.
- `ApiKeyFilter` is no longer a servlet filter. It runs only in the `local` profile's chain (`application-local.properties`, `HELPDESK_API_KEY`): with a key set, `X-API-Key` must match (401 otherwise); with a blank key every request is let in as a local user with all roles, and a WARN is logged at startup.
- Tests: `SecurityConfigTests` (real RS256 tokens: valid, missing, garbage, wrong key, wrong audience, expired; deny by default for unknown paths; headers; 403 problem; fail-fast), `JwkSetUriDecoderTests`, `LocalSecurityTests`, `ActuatorSecurityTests` (real ports: management port open, app port 401, JWK-set-URI decoder wired). Controller tests now use `jwt()`.

`./mvnw -pl assistant -am verify` passes.

**Breaking for deployments:** the assistant no longer starts without `OIDC_ISSUER_URI` unless `SPRING_PROFILES_ACTIVE=local`.

Needs from stream C:
- `docker-compose.yml`: until C6/C7 land, set `SPRING_PROFILES_ACTIVE: local` on the `assistant` service (keeps the current `HELPDESK_API_KEY` + nginx `X-API-Key` flow working). With C6/C7, drop `local` and set `OIDC_ISSUER_URI=http://localhost:8180/realms/helpdesk`, `OIDC_JWK_SET_URI=http://keycloak:8080/realms/helpdesk/protocol/openid-connect/certs` and `OIDC_AUDIENCE=helpdesk-assistant`.
- README: document `OIDC_ISSUER_URI`, `OIDC_AUDIENCE`, `OIDC_JWK_SET_URI`, the `local` profile and the fact that `HELPDESK_API_KEY` only works in `local`.
- Any future Helm/K8s values: `OIDC_ISSUER_URI` is required in `prod`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

### `prod/a6-jwt-principal` (base: `prod/a5-resource-server`)
Stacked on A5 because it changes the same `SecurityConfig`, `ApiKeyFilter`, `application.properties` and `SecurityConfigTests`. Merge A5 first, then retarget this PR to `main`.

**PR title:** Map the JWT to a HelpdeskPrincipal with roles and allowed customers (A6)

**PR body:**
Stream A, task A6 (T1.2). Depends on #<A5 PR> (stacked on `prod/a5-resource-server`; retarget to `main` once A5 is merged).

- New `HelpdeskPrincipal` (`subject`, `username`, `roles`, `customers`, `allCustomers`) with `hasRole`, `canAccess(customer)` (case-insensitive) and `HelpdeskPrincipal.of(Authentication)`. A7 and A8 will use it for authorization and tool scoping.
- New `HelpdeskJwtConverter`, wired into the resource server. Per K1, each entry of the `roles` claim becomes an authority with the same name (`helpdesk-agent`, `helpdesk-admin`, `tickets-write`; no `ROLE_` prefix, so use `hasAuthority`). The `customers` claim becomes a lower-cased allowed set, and `"*"` means all customers. A claim may be a JSON array or a space/comma-separated string. A missing claim means no roles or no customers. A token without `sub` gets 401. `username` is `preferred_username`, falling back to `sub`, and `getName()` is the `sub`.
- Claim names are configurable: `helpdesk.security.roles-claim` / `helpdesk.security.customers-claim` (env `OIDC_ROLES_CLAIM` / `OIDC_CUSTOMERS_CLAIM`, defaults `roles` / `customers`). A dotted name reads a nested claim, e.g. Keycloak's `realm_access.roles`.
- The `local`-profile user is now `HelpdeskPrincipal.LOCAL` (all roles, all customers), so later authorization code handles both profiles the same way.
- Tests: `HelpdeskJwtConverterTests`; `SecurityConfigTests.tokenClaimsBecomeTheHelpdeskPrincipal` (a real signed token through the chain); `LocalSecurityTests`; `HelpdeskPropertiesTests`.

`./mvnw -pl assistant -am verify` passes.

Needs from stream C:
- README: document `OIDC_ROLES_CLAIM` and `OIDC_CUSTOMERS_CLAIM` (optional; the defaults match the K1 realm).

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
A7 – dependencies satisfied: yes (needs A5 and A6; branch from `prod/a6-jwt-principal` if they are not merged yet)

## Blocked on
nothing

## Notes for the next session
- Authorities are the plain role names (`hasAuthority("helpdesk-admin")`), with no `ROLE_` prefix. Role constants are `SecurityConfig.ROLE_AGENT/ROLE_ADMIN/ROLE_WRITER`. Use `HelpdeskPrincipal.of(authentication)` in controllers (or `@AuthenticationPrincipal HelpdeskPrincipal`). It is never null behind the app chains, in either profile.
- 403 is already rendered as a problem by `SecurityConfig.problemDenied()`. For A7, a `@PreAuthorize`/`AccessDeniedException` thrown inside MVC goes to `ApiExceptionHandler`'s catch-all (500) unless you add a handler for `AccessDeniedException` there.
- WebMvc tests: `@Import({SecurityConfig.class, RequestIdFilter.class, ApiExceptionHandler.class})`, `@MockitoBean JwtDecoder jwtDecoder`, and `.with(jwt().authorities(...))`. Note that `jwt()` does NOT run `HelpdeskJwtConverter`, so the principal will be a `Jwt`, not a `HelpdeskPrincipal`. For A7 tests, either use `.with(authentication(new HelpdeskJwtConverter(...).convert(jwt)))` or the real signed tokens from `SecurityConfigTests.token(...)`.
- Boot's `@ConditionalOnProperty` matches an empty `jwk-set-uri`, so the property is `helpdesk.security.jwk-set-uri` with our own decoder bean (`SecurityConfig.jwkSetUriJwtDecoder`), not Boot's.
- The worktree sandbox rejects compound shell commands with heredocs plus `cd`/git. Write Python edit scripts to the scratchpad and run them with `python3 <script>`.
- Run `./mvnw com.spotify.fmt:fmt-maven-plugin:format -pl assistant` before `./mvnw -pl assistant -am verify`.
