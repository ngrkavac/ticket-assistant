# CLAUDE.md

## Project

Helpdesk assistant ("Resolva"). It has these modules:
- `assistant`: Spring Boot. RAG plus an MCP client, using Ollama.
- `ticket-mcp-server-java`, `-python` and `-rust`: one ticket MCP server contract, implemented three times.
- `request-log-mcp-server`: Spring Boot with Postgres and Flyway.
- `ui`: React with Vite, served by nginx.
- `ticket-mcp-contract-tests`: pytest black-box suite for the ticket servers.

Build and test:
- Java: `./mvnw verify`. This runs the tests, the 80% JaCoCo line-coverage gate and the google-java-format check. Fix formatting with `./mvnw com.spotify.fmt:fmt-maven-plugin:format`.
- UI: `cd ui && npm run lint && npm test && npm run build`.
- Python: `cd ticket-mcp-server-python && uv run ruff check . && uv run ruff format --check . && uv run pytest`.
- Rust: `cd ticket-mcp-server-rust && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test`.
- Contract: start a ticket server on :8081, then `cd ticket-mcp-contract-tests && TICKETS_MCP_URL=http://localhost:8081 uv run pytest`.

Plans live in the repo root as `<FEATURE>_PLAN.md`.

## Git: the user owns every push

- **Never run `git push`** in any form (no force, no tags, no new branches), and never do anything else that writes to the remote: `gh pr create`, `gh pr merge`, `gh release`, and so on. Pushing, opening PRs and merging are always the user's job.
- Commit locally only. Commit on `main` only when the user explicitly asks; otherwise use a branch.
- When work is ready for the remote, say which branch(es) to push and give the PR title and body for the user to use.

## Production-readiness work (multi-agent)

- The source of truth is `PRODUCTION_READINESS_PLAN.md`, section **"Work breakdown: 3 parallel streams"**. Each agent works on exactly one stream (A, B or C), named in its launch prompt. If no stream was named, ask before doing anything.
- Work only in your stream's git worktree. Use **one local branch per task**, named `prod/<task-id>-<slug>` (e.g. `prod/a1-catalog-length`), created from the latest `main`. Only if the task needs your own unmerged earlier task, branch from that task's branch and say so in the PR text. Each branch becomes one PR, which the user pushes and opens (see "Git" above).
- **Never edit files owned by another stream.** The ownership table is in the plan. If you need a change in another stream's file, write it under "Needs from stream X" in the PR text.
- Never edit `PRODUCTION_READINESS_PLAN.md`, not even its checkboxes. Neighbouring checkbox edits made on separate branches always conflict. Record progress only in your own `handoff/stream-<x>.md`. The main session ticks the plan's checkboxes in one commit after the user merges a batch of PRs.
- Shared contracts K1–K9 in the plan may only change in a backwards-compatible way (new params optional, nothing removed). A breaking change needs a note in the plan and the user's approval.
- Never deploy anything.

## Session limit (mandatory)

Long sessions degrade. Work is split into short sessions that hand off through a file, so each session starts with fresh context.

1. **Start of session:** read `handoff/stream-<x>.md` and your stream's task list in the plan. A task is finished if it is ticked in the plan **or** listed as done in your handoff (the plan is ticked only after merge). Pick the first unfinished task whose ⛓ cross-stream dependencies are merged into `main`. If the previous task's branch is not merged yet and the new task touches the same files, branch from it and say so in the PR text. If an earlier session left work in progress, continue that first.
2. **Limit:** finish **at most 2 tasks per session**. A task marked **[L]** (large) counts as 2, so a session that takes one does nothing else.
3. **Stop early** at the next safe point (tests green, work committed) in any of these cases:
   - the conversation was automatically compacted or summarized (you see a summary of earlier context);
   - the current task is blocked on another stream;
   - you are going in circles on the same failure (3 failed attempts at one problem).
4. A task is **done** only when:
   - the code and tests are written;
   - the relevant build/test commands above pass;
   - it is listed under "Done this session" in your handoff file;
   - everything is committed on the task branch;
   - its PR title and body are written under "Ready to push" in the handoff file.
5. **Never start another task once the limit is reached**, even a small one.
6. **Before stopping:**
   - Overwrite `handoff/stream-<x>.md` using the template below and commit it on your last task branch.
   - If a task is unfinished, commit the work in progress to its branch with a `WIP:` commit message, mark it "not ready to push" and describe its exact state in the handoff.
7. **End the session** with a 3–5 line summary followed by this exact line:
   `SESSION LIMIT REACHED – start a fresh session for stream <x>`

### Handoff template (`handoff/stream-<x>.md`)

```
# Stream <x> handoff – <YYYY-MM-DD>
## Done this session
<task ids + branch names, or "none">
## Ready to push
<per branch: branch name, base branch, PR title, PR body – or "none">
## In progress
<task id, branch, what works, what is left – or "none">
## Next task
<id> – dependencies satisfied: yes/no
## Blocked on
<other stream task ids, or "nothing">
## Notes for the next session
<decisions made, failing tests, useful commands, surprises>
```
