# Figma implementation plan: basic React UI for the helpdesk assistant

## Context
The project is backend-only today (Spring Boot `assistant` on :8080 with `POST /ask`, plus two MCP servers). You want a minimal web UI — a dropdown to pick a question and a list of past questions from the request-log DB — mainly as an exercise to test the Figma plugin + Figma MCP connection with Claude Code. The UI is designed in Figma first (no template; a plain layout is fine), then Claude reads the design through the Figma MCP server and generates the React components.

One gap to close: the browser cannot talk to the request-log MCP server (SSE/MCP, no auth, deliberately private, and its tools are hidden from the model). So the assistant gets a small read-only REST endpoint that reuses its existing MCP client connection.

## Part A — What you need to do (Figma setup, one-time)

1. **Install Figma desktop app** (macOS): download from figma.com/downloads, or `brew install --cask figma`. Sign in.
2. **Check your seat**: the *desktop* MCP server needs a Dev or Full seat on a paid plan. On a free/Starter plan use the *remote* server (step 4b) — it works on all plans, with lower rate limits.
3. **Create the design file** (any simple layout — Claude only needs one frame):
   - New design file → `Helpdesk UI`, one Desktop frame (1280×800) named `Helpdesk`.
   - Inside: title text "Helpdesk Assistant"; a dropdown (rectangle + text "Select a question…" + chevron); an "Ask" button; an answer card; a "Recent questions" list with 2–3 rows (question text, status pill SUCCESS/ERROR, time).
   - Optional: turn the dropdown, button, row and status pill into components — the MCP output maps them more cleanly.
   - (If the Figma MCP server in your session exposes canvas-write tools, I can draft this frame for you instead — we'll check once it's connected.)
4. **Connect Figma to Claude Code** — pick one:
   - **a) Claude Code Figma plugin (recommended, bundles MCP server + skills):** in Claude Code run `/plugin`, find **figma** in the official marketplace, install, then restart Claude Code.
   - **b) Remote MCP server manually:** `claude mcp add --transport http figma https://mcp.figma.com/mcp`, then `/mcp` → figma → *Authenticate* (browser OAuth).
   - **c) Desktop MCP server:** Figma desktop → open the file → toggle **Dev Mode** (Shift+D) → Inspect panel → **Enable desktop MCP server**. Then `claude mcp add --transport http figma-desktop http://127.0.0.1:3845/mcp`. Figma desktop must stay open while using it.
5. **Verify**: run `/mcp` in Claude Code — `figma` should show *connected* with tools like `get_design_context`, `get_screenshot`, `get_variable_defs`. In Figma, select the `Helpdesk` frame → right-click → *Copy link to selection*, and paste that link to me.
6. Install **Node.js 20+** (`brew install node`) for the React app.

## Part B — Implementation (what I'll do)

### B1. Backend: read-only history endpoint on the assistant
- `assistant/.../RequestLogClient.java`: add `List<RequestLogEntryView> recent(int limit)` — synchronous `client.callTool(new CallToolRequest("listRecentRequests", Map.of("limit", limit)))`, parse the JSON `TextContent` with Jackson into a new record. Throws/returns empty when logging is disabled.
- New `assistant/.../RequestHistoryController.java`: `GET /requests?limit=20` (1–100) → `[{requestId, question, status, createdAt}]`. Deliberately **omit `answer`** and `errorType` to limit data exposure; 503 via `ApiExceptionHandler` if the log server fails.
- `ApiKeyFilter.shouldNotFilter`: also protect `/requests` (currently only `/ask`).
- Tests: extend `RequestLogClientTests` (parse tool result, disabled case) and add a `@WebMvcTest` for the controller (limit validation, API key required) following `AssistantControllerTests`.
- README / HOW_IT_WORKS: document `GET /requests` and the UI.

### B2. Frontend: `ui/` (Vite + React + TypeScript, at repo root, outside Maven)
- Scaffold: `npm create vite@latest ui -- --template react-ts`.
- **Vite dev proxy** (`ui/vite.config.ts`): `/ask` and `/requests` → `http://localhost:8080`, injecting `X-API-Key` from `HELPDESK_API_KEY` (read from env in the proxy). The key never ships in the browser bundle and no CORS config is needed in Spring.
- Components (generated from the Figma frame via `get_design_context` + `get_screenshot`, adapted to plain CSS, no extra UI library):
  - `QuestionSelect.tsx` — `<select>` with predefined questions (the README examples: VPN dropping, ticket T-1003 status, acme open tickets, password reset, laptop hardware) + Ask button.
  - `AnswerCard.tsx` — loading / answer / error states (shows 401/429/503 messages).
  - `RecentRequests.tsx` — list from `GET /requests`, newest first, status pill + relative time.
  - `App.tsx` — wires them; after an answer, re-fetches history after ~500 ms (log write is async).
  - `api.ts` — `ask(question)` and `listRequests(limit)` with typed responses.
- Design tokens: pull colors/spacing via `get_variable_defs` if you define variables; otherwise use values from the generated code into `index.css` custom properties.
- `ui/README.md` section: `cd ui && npm install && HELPDESK_API_KEY=… npm run dev` → http://localhost:5173.
- Optional (only if you want it): a Node job in `.github/workflows/ci.yml` running `npm ci && npm run build`.

### Order of work
1. You finish Part A and send the frame link.
2. I implement B1 (backend, testable without Figma).
3. I pull the design via Figma MCP and generate B2 to match it; compare against `get_screenshot`.

## Verification
- `./mvnw -q test` — all modules green, new tests included.
- Run the three services (README "Run"), then `curl -s localhost:8080/requests -H "X-API-Key: $HELPDESK_API_KEY"` → JSON list; without the header → 401.
- `cd ui && HELPDESK_API_KEY=… npm run dev`, open http://localhost:5173: pick a question → Ask → answer appears → it shows at the top of "Recent questions".
- Cross-check the same row via Claude Code MCP: `listRecentRequests` on the `requestlog` server.
- `npm run build` passes (type-check).
- Visual: side-by-side with the Figma frame screenshot.
