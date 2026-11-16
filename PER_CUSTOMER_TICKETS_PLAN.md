# Per-customer tickets for all three ticket MCP servers

## Context
The ticket MCP server exists in Java, Python and Rust (same wire contract, checked by
`ticket-mcp-contract-tests`). Each hard-codes 4 tickets in a flat id -> ticket map. We want
more tickets, a way to create them, and the customer name as the primary key so every
operation is scoped to one customer. Decisions you made:
- Seed data grows to 3 customers / 10 tickets, plus a `createTicket` tool (in memory).
- Storage is keyed by customer; every ticket tool takes a customer; `getTicket(customer, id)`
  fails if the ticket belongs to someone else.
- New tools: `listCustomers`, `listTickets(customer, status?)`.
- Create: server assigns the next global id (T-1011, ...), known customers only.
- Also update contract tests, assistant prompt, UI sample questions, docs.
- Work on a feature branch, no commit.

## Shared contract (all three servers must match exactly)

Storage: customer (lowercase) -> (id -> Ticket), sorted. Ticket JSON unchanged
(`id, customer, subject, status, priority`, compact, same field order). Thread-safe writes.
String semantics stay Java-style (`java_trim`, `java_is_blank`, ASCII `\d`) using the
existing helpers in `tickets.py` / `tickets.rs`. Customer matching: trim + case-insensitive.

Seed data (T-1001..T-1004 unchanged):
| id | customer | subject | status | priority |
|---|---|---|---|---|
| T-1005 | acme | Outlook keeps asking for credentials | OPEN | MEDIUM |
| T-1006 | globex | Monitor flickering | RESOLVED | LOW |
| T-1007 | initech | Shared drive not mapping after login | OPEN | HIGH |
| T-1008 | initech | Request new laptop for new hire | IN_PROGRESS | MEDIUM |
| T-1009 | initech | Wi-Fi slow in meeting room B | RESOLVED | LOW |
| T-1010 | acme | MFA codes not arriving by SMS | IN_PROGRESS | HIGH |

Tools (param order = schema property order; descriptions verbatim):
| tool | params (required unless noted) | description | result |
|---|---|---|---|
| `getTicket` | `customer` "Customer name, e.g. acme", `id` "Ticket id such as T-1001" | "Get a support ticket of a customer by its id, e.g. T-1001. Fails with a message if the customer has no such ticket." | ticket |
| `listOpenTickets` | `customer` (unchanged) | unchanged | tickets not RESOLVED, by id |
| `listTickets` | `customer`; `status` optional "Optional status filter: OPEN, IN_PROGRESS or RESOLVED" | "List all tickets of a customer, optionally filtered by status." | tickets, by id |
| `listCustomers` | none | "List the names of all known customers." | `["acme","globex","initech"]` sorted |
| `createTicket` | `customer`, `subject` "Short description of the problem", `priority` "Priority: LOW, MEDIUM or HIGH" | "Create a new OPEN support ticket for a known customer and return it. The ticket id is assigned by the server." | created ticket |

Validation, in this order, with exact error texts:
- customer null/blank -> `Customer name is required` (all customer tools)
- getTicket: bad id -> `Invalid ticket id, expected format T-1234`; unknown customer or ticket
  of another customer -> `Ticket <trimmed id> not found` (no cross-customer leak)
- listTickets: status null/blank -> no filter; else trim + uppercase; not one of the three ->
  `Invalid status, expected one of OPEN, IN_PROGRESS, RESOLVED` (checked before the customer
  lookup, so an unknown customer with a bad status gets this error, not `[]`)
- listOpenTickets / listTickets: unknown customer -> `[]`
- createTicket: unknown customer -> `Unknown customer <trimmed customer>`; subject null/blank ->
  `Subject is required`; trimmed subject > 200 code points (Java: `codePointCount`) ->
  `Subject must be at most 200 characters`; priority not LOW/MEDIUM/HIGH (trim, case-insensitive)
  -> `Invalid priority, expected one of LOW, MEDIUM, HIGH`. New ticket: id `T-(max existing + 1)`,
  customer = canonical stored name, trimmed subject, status OPEN, uppercase priority.
- Non-string scalars are coerced to strings as today (`_as_string` in Python, Rust equivalent).

## Execution
1. `git checkout -b feature/per-customer-tickets`.
2. Spawn 3 `general-purpose` subagents in parallel (same tree, disjoint dirs), each given the
   contract above verbatim:
   - **Java** — `ticket-mcp-server-java/`: `Ticket.java`, `TicketTools.java` (customer-keyed
     `ConcurrentHashMap`/synchronized store, `@ToolParam(required = false)` for status),
     `TicketToolsTests.java`. Run `./mvnw -pl ticket-mcp-server-java verify` (incl.
     google-java-format check). Report the actual `tools/list` JSON schemas it generates.
   - **Python** — `ticket-mcp-server-python/`: `tickets.py` (store + functions), `server.py`
     (`TOOLS` schemas, `call` dispatch), `tests/test_tickets.py`. Run `uv run pytest && uv run ruff check .`.
   - **Rust** — `ticket-mcp-server-rust/`: `tickets.rs` (replace const `TICKETS` with a
     `Mutex`/`RwLock` store), `protocol.rs` (tool defs + dispatch), unit tests. Run
     `cargo test && cargo clippy -- -D warnings` and `cargo fmt --check`.
   - Each: no git commits, don't start a server on :8081, don't touch other modules.
3. Meanwhile (me): update `ticket-mcp-contract-tests/tests/test_contract.py` — 5 tools and
   schemas, new seed expectations (acme open = T-1001, T-1005, T-1010), `getTicket` with
   customer incl. cross-customer not-found, `listTickets` filters + invalid status,
   `listCustomers`, `createTicket` happy path (assert id format and > T-1010, not an exact id,
   since the server is stateful across runs) and each error. Create tests use `initech` and
   avoid exact-list assertions on initech so reruns stay green.
4. Update `assistant/.../ChatConfig.java` SYSTEM prompt (ticket tools need a customer; ask for
   it if missing; only create a ticket when the user explicitly asks), `ui/src/questions.ts`
   (e.g. "What is the status of globex ticket T-1003?"), `README.md`, `HOW_IT_WORKS.md`
   (tool lists, examples like `getTicket {"customer":"globex","id":"T-1003"}`).
5. Align schemas if the Java agent's real Spring AI output differs from the hand-written ones.

## Verification
- Each module's own build/tests (above).
- Run each server in turn on :8081 and `cd ticket-mcp-contract-tests && TICKETS_MCP_URL=http://localhost:8081 uv run pytest` — all three must pass, run twice to prove rerun-safety.
- `./mvnw -pl assistant verify` for the prompt change; `ui` type check/build.
