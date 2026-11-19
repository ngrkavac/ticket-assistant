# Customer-scoped questions in the UI: plan


## Context
The ticket MCP servers now keep tickets per customer and expose `listCustomers`, `listTickets` and `createTicket`. The UI hasn't caught up. It has one hard-coded question dropdown, the questions name customers in their own text, and the history table shows the last 20 requests for everyone. The goal:
- A **customer dropdown** (filled from the ticket server's `listCustomers`). Every question is asked *for* that customer.
- **Multi-select of questions**. The **Add** button asks each selected question in turn, and each one becomes a row in the table, as a single question does today.
- **Add a new question** to the selectable list. It's **stored in the backend**, so it's shared across browsers.
- The **table shows only the last 5 requests for the selected customer**.

Decisions confirmed with you: the "features" are the questions; the customer is stored in the request log and filtered on the server; new questions go to backend storage; predefined questions become customer-neutral, and the selected customer is injected into each request.

## 1. request-log-mcp-server (Java, Postgres/H2 + Flyway)
- **Migration `V2__customer_and_questions.sql`**:
  - `ALTER TABLE request_log ADD COLUMN customer VARCHAR(64)` (nullable, so old rows remain valid) plus `CREATE INDEX idx_request_log_customer_created ON request_log (customer, created_at DESC)`.
  - New table `question (id BIGINT identity PK, text VARCHAR(1000) NOT NULL UNIQUE, created_at TIMESTAMPTZ default now)`, seeded with the neutral questions:
    - "My VPN keeps dropping, what should I try?"
    - "How do I reset my password?"
    - "My laptop will not turn on, what should I do?"
    - "What open tickets do we have, and how do I fix the first one?"
    - "List all our tickets with their status."
    - "Create a HIGH priority ticket: the badge reader at the entrance is broken."
- `RequestLogEntry`: add a `customer` field. `RequestLogRepository.save(...)` takes the customer. `findRecent(limit, customer)` adds `WHERE customer = :customer` when the customer isn't null.
- `RequestLogTools`:
  - `logRequest` gets an optional `customer`, validated against `[a-z0-9-]{1,64}` after trim and lower-casing.
  - `listRecentRequests` gets an optional `customer` filter.
  - New `listQuestions()` returns `[{id, text, createdAt}]`, oldest first.
  - New `addQuestion(text)` trims the text, requires it to be non-blank and at most 1000 chars, and is **idempotent**: if the same text already exists it returns the existing row.
- New `QuestionRepository` and `Question` record, written in the same JdbcClient style as `RequestLogRepository`.
- `RequestLogToolsTests`: customer stored and filtered, invalid customer rejected, `addQuestion` dedupes and rejects blank or too-long text, seeded questions listed, new tools exposed.
- `.claude/settings.json`: deny `mcp__requestlog__addQuestion`, the same way `logRequest` is denied, so developer access stays read-only.

## 2. assistant (Spring Boot)
- **`TicketDirectoryClient`** (new) calls the ticket MCP `listCustomers` tool directly on the `tickets` connection. It reuses `RequestLogClient.isConnection` and parses the JSON with Jackson. Failures raise a new `TicketsUnavailableException`, which `ApiExceptionHandler` maps to 503.
- **`GET /customers`** in the new `CustomerController` returns `["acme","globex","initech"]`.
- **Questions**: `RequestLogClient` gets `questions()` and `addQuestion(text)`, both synchronous like `recent()`. A new `QuestionController` serves:
  - `GET /questions` returns `[{id, text}]`.
  - `POST /questions {text}` validates the text (`@NotBlank`, at most `helpdesk.max-question-length`), then returns 201 with `{id, text}`.
- **`/ask`**: the `Question` record becomes `{question, customer?}`. If a customer is given, it must be one of `TicketDirectoryClient.customers()`; otherwise the request fails with 400 "Unknown customer". `AssistantService.ask(question, customer)` sends the user message as `"Customer: <customer>\n\n<question>"` and logs the **original** question together with the customer.
- **`GET /requests`** gets an optional `customer` parameter (same pattern) and passes it through to `listRecentRequests`. The UI asks for `limit=5`.
- **System prompt (`ChatConfig.SYSTEM`)** is adjusted: "Messages may start with `Customer: <name>`. That's the customer the user belongs to; use it for ticket tools and don't ask for it again. Still don't call ticket tools for general how-to questions."
- **`ApiKeyFilter`** also protects `/customers` and `/questions`.
- Tests: add cases to `AssistantControllerTests` (customer is passed through, unknown customer gets 400), `AssistantServiceTests` (prompt prefix, logged question unprefixed), `RequestHistoryControllerTests` (customer filter), `RequestLogClientTests` (customer argument, question tools). Add new `QuestionControllerTests` and `CustomerControllerTests` (API key, validation, 503).

## 3. UI (`ui/`)
- `api.ts`:
  - `listCustomers()`, `listQuestions()`, `addQuestion(text)`.
  - `ask(question, customer)`.
  - `listRequests(customer, limit = 5)`.
  - `LoggedRequest` gets `customer`.
- `vite.config.ts` and `nginx.conf.template`: proxy `/customers` and `/questions` too (nginx regex becomes `^/(ask|requests|customers|questions)$`).
- `App.tsx`: the selected customer lives in state, defaulting to the first customer. Questions are loaded from the backend. When the customer changes, the history reloads. After each ask completes, the history reloads.
- **`CustomerPicker.tsx`** (new): a dropdown at the top, styled with the existing `.select` classes.
- **`QuestionPicker.tsx`** becomes a checkbox list of questions with an **Add** button. Add runs the selected questions **one after another** (a local model is slow and the server has a concurrency cap) and shows progress in the existing status hint: "Asking 2 of 3…". A failed question is reported and the run moves on to the next one. Selections are cleared once the run finishes.
- **`NewQuestionForm.tsx`** (new): a text input plus an **Add to list** button. It calls `POST /questions`, appends the result to the list and pre-selects it. Errors (blank, too long, duplicate) show inline.
- `HistoryTable.tsx`: the heading becomes "Last 5 for <customer>". The hint text changes to match, and the empty state reads "No questions asked for <customer> yet."
- `questions.ts` is deleted, because the questions now come from the backend.
- `index.css`: styles for the checkbox list and text input, reusing the existing tokens, plus a mobile layout.

## 4. Docs
README and HOW_IT_WORKS get the new endpoints (`/customers`, `/questions`, `customer` on `/ask` and `/requests`), the new request-log tools and the customer injection.

## Verification
- `./mvnw -q test`: all modules pass, including the new tests and the google-java-format check.
- `cd ui && npm run build`: type-check and build pass.
- `cd ticket-mcp-contract-tests` contract tests are unchanged and still pass (no ticket-server changes).
- End to end: `COMPOSE_PROFILES=java docker compose up --build`, then open http://localhost:5173.
  1. Pick globex and select two questions, then click Add. Both are asked in turn and appear in the table, with a maximum of 5 rows.
  2. Switch to acme: the table shows only acme's requests.
  3. Add a new question: it appears in the list. Reload the page and it's still there. Open another browser and it's there too.
  4. `curl -s 'localhost:8080/requests?customer=globex&limit=5' -H "X-API-Key: $HELPDESK_API_KEY"` returns only globex rows. Without the key it returns 401.
