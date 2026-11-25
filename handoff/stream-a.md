# Stream a handoff – 2026-11-25
## Done this session
- A8 [L] – `prod/a8-customer-scoped-tools` (base `main` 4dc972d)

## Ready to push
### `prod/a8-customer-scoped-tools` (base: `main`)
**PR title:** Scope ticket tools to the request's customer via the ToolContext (A8)

**PR body:**
Stream A, task A8 (T1.4, server-side tool scoping).

- New `CustomerScopedToolCallback` wraps every ticket tool the model can call (`ChatConfig.customerScoped`). It reads the request's customer and the `HelpdeskPrincipal` from the Spring AI `ToolContext`, never from the model. The model's `customer` argument is filled in when missing or blank and normalized when it matches. Any other value gets the tool error `Not allowed for this customer`, a WARN log (the model's value is sanitized and truncated) and `helpdesk_tool_denied_total{tool}` (K9). The ticket server is not called in that case.
- Fails closed: a call with no customer in the tool context, no principal, or a principal that may not act for that customer is refused the same way. Tools without a `customer` argument are not offered to the model at all.
- The tool context is **not** forwarded to the MCP call. Spring AI's default converter would otherwise copy it, including the principal, into the request's `_meta`.
- `listCustomers` is hidden from the model (the `McpToolFilter` bean is now `hideInternalTools`). `TicketDirectoryClient` still calls it directly for `/customers` and the `/ask` check.
- `AssistantService.ask(question, customer, principal)`: the user text is now the question as typed. The `Customer: <name>` line is gone. The customer is a `{customer}` parameter of the system prompt and is in the tool context together with the principal. Customer and principal are required. `AssistantController` passes the principal through.
- System prompt rewritten: the assistant works only for `{customer}`, always passes that customer to the ticket tools, never asks for it, and declines questions about other customers' tickets.
- Tests: `CustomerScopedToolCallbackTests` (fill in, normalize, deny plus the metric, no context or principal, admin, context not forwarded, invalid JSON, schema check), `ChatConfigTests` (wrapping/dropping, `listCustomers` hidden), `AssistantServiceTests` now use a real `ChatClient` over a fake model to check the user text, the rendered system prompt and the tool context. There is also a new `AssistantIntegrationTests` case. 143 tests, coverage gate met.
- No contract change: K3 is unchanged and `helpdesk_tool_denied_total{tool}` is the name from K9. The full cross-customer tool-call test with a fake tool-calling model is A12.

**Needs from stream C (docs):** `HOW_IT_WORKS.md` (line ~100: "`AssistantService` then sends `Customer: <name>`…", the tool table at ~123 and the example at ~160 that uses `listCustomers` through the model) and `README.md` (line ~108: "The model receives `Customer: <name>` in front of the question") describe the old behaviour. The customer now reaches the model only through the system prompt and the tool context, every ticket tool call is limited to it, and `listCustomers` is no longer offered to the model.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## In progress
none

## Next task
A9 (T1.5: offer `createTicket` only with `tickets-write`, plus tool-call audit records) – dependencies satisfied: yes (no ⛓). If A8 is not merged yet, branch A9 from `prod/a8-customer-scoped-tools`, because both touch `ChatConfig`/`AssistantService`/`CustomerScopedToolCallback`.

## Blocked on
nothing

## Notes for the next session
- Tool scoping lives in `CustomerScopedToolCallback`. The ToolContext keys are `CustomerScopedToolCallback.CUSTOMER` and `PRINCIPAL`. For T1.5 write gating (`createTicket` only with `tickets-write`), filter the offered tools per request. Note that `ChatConfig.customerScoped(..)` is a `defaultToolCallbacks` provider resolved per request but without request data, so per-principal filtering needs `chat.prompt().toolCallbacks(..)`/`toolNames(..)` in `AssistantService` or a check inside the callback using the principal from the ToolContext.
- The system prompt is an ST template with the `{customer}` param (`ChatConfig.SYSTEM`/`CUSTOMER_PARAM`). Every prompt must set it, or rendering throws. Do not add other `{...}` to the system text unless you set it too.
- `AssistantServiceTests` builds a real `ChatClient.builder(fakeModel).defaultSystem(ChatConfig.SYSTEM)`, so it is easy to inspect the `Prompt` (system text, `ToolCallingChatOptions.getToolContext()`).
- Denied tool calls go back to the model as the text "Not allowed for this customer" via the existing `ToolExecutionExceptionProcessor` (which also logs WARN "Tool X failed").
- The worktree sandbox rejects compound shell commands that mix `cd`/heredocs/git, and also any command containing the word `eval` (e.g. `ls eval`). Use plain separate commands and the Write tool for scripts.
- Run `./mvnw com.spotify.fmt:fmt-maven-plugin:format -pl assistant` before `./mvnw -pl assistant -am verify` (143 tests, green at the A8 commit).
