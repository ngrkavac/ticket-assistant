# Stream a handoff – 2026-11-25
## Done this session
- A7 – `prod/a7-endpoint-authz` (base `main` d704c75)

## Ready to push
### `prod/a7-endpoint-authz` (base: `main`)
**PR title:** Authorize endpoints by role and customer per K3 (A7)

**PR body:**
Stream A, task A7 (T1.3). Implements contract K3 on the assistant side.

- `SecurityConfig`: `POST /ask` needs `helpdesk-agent`. `POST /questions` needs `helpdesk-admin`. Every other endpoint on the app port (the reads, plus endpoints added later) needs `helpdesk-agent` or `helpdesk-admin`. A valid token without a helpdesk role now gets a 403 problem instead of reaching the controllers. Actuator on the management port is unchanged.
- `POST /ask`: `customer` is now **required** (400 if missing or blank). A customer that is not in the token gets a 403 with `detail: "Not allowed for this customer"`. This check runs before the ticket server is asked, so callers cannot probe which customers exist. An allowed but unknown customer still gets a 400. The response stays `{answer}`.
- `GET /customers` returns only the customers in the token, or all of them for `customers: ["*"]`.
- `GET /requests?customer=` gets the same 403 check. Leaving out `customer` (all customers) is allowed only for users with `customers: ["*"]`.
- New `CustomerNotAllowedException` (an `AccessDeniedException`) and `HelpdeskPrincipal.require(..)`/`requireAccess(..)`. `ApiExceptionHandler` maps both to 403 problems instead of the catch-all 500.
- The `local` profile is unchanged: the local user has every role and every customer.
- Tests: a new `TestUsers` helper authenticates MockMvc requests with a real `HelpdeskPrincipal`. There are new role and customer cases for every endpoint, real signed-token cases in `SecurityConfigTests`, and a cross-customer 403 in `AssistantIntegrationTests`. The full endpoint × role × customer matrix is A12.

`OIDC_JWK_SET_URI` (asked for in the C6 PR) already shipped with A5, so this PR needs no K8 change.

**Needs from stream C (UI, C7):** the UI already sends `customer` on every `/ask` and shows the problem `detail` on errors, so nothing breaks. Nice to have: hide or disable "add question" for users without `helpdesk-admin` (it now returns 403), and treat a 403 on `/ask` as "not allowed for this customer" rather than a generic failure.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
A8 [L] – dependencies satisfied: yes. A8 is [L], so it needs a session of its own. If A7 is not merged yet, branch A8 from `prod/a7-endpoint-authz`, because both touch `AssistantController`/`AssistantService`.

## Blocked on
nothing

## Notes for the next session
- Authorization layout: role rules live in `SecurityConfig.api()` (URL + method). Customer checks happen in the controllers through `HelpdeskPrincipal.require(authentication).requireAccess(customer)`, which throws `CustomerNotAllowedException` (403, detail "Not allowed for this customer").
- Tests: use `TestUsers.agent("acme")`, `TestUsers.admin()` (agent+admin, all customers) or `TestUsers.user(roles, customers, all)` instead of `jwt()`. `jwt()` has no `HelpdeskPrincipal`, so controllers answer 403 "Access denied." to it. `SecurityConfigTests.acmeAgentToken()` gives a real signed token.
- `AssistantIntegrationTests` now mocks `TicketDirectoryClient` (there is no MCP server there), which is useful for the A8/A12 cross-customer tool-call test.
- `AssistantService.ask` still takes a nullable customer and puts it in the user text as `Customer: ...`. A8 removes that and passes it through the ToolContext instead. The customer is now always non-null when it comes from `/ask`.
- The worktree sandbox rejects compound shell commands that mix heredocs with `cd`/git. Write edit scripts with the Write tool into the scratchpad and run them with `python3 <script>`.
- Run `./mvnw com.spotify.fmt:fmt-maven-plugin:format -pl assistant` before `./mvnw -pl assistant -am verify` (124 tests, green at the A7 commit).
