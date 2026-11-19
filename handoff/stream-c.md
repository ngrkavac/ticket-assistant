# Stream c handoff – 2026-11-19
## Done this session
- C1 – `prod/c1-dockerfile-jar-glob` (base `main`)
- C2 – `prod/c2-show-answers` (base `prod/c1-dockerfile-jar-glob`, stacked; see notes)

## Ready to push
### `prod/c1-dockerfile-jar-glob` (base: `main`)
**PR title:** Fix Dockerfile jar selection so release versions build (C1 / T0.2)

**PR body:**
The build stage extracted `${MODULE}/target/${MODULE}-*-SNAPSHOT.jar`, so any non-SNAPSHOT version (e.g. `1.0.0`) broke the image build (finding E3).

- Select the single executable jar in `${MODULE}/target` for any version, skipping `*-plain.jar`, `*-sources.jar` and `*-javadoc.jar`.
- Fail with a clear message if there is not exactly one candidate.

Verified:
- `docker build --build-arg MODULE=assistant .` at the current `0.0.1-SNAPSHOT`.
- With all poms set to `1.0.0` (scratch copy): `assistant`, `request-log-mcp-server` and `ticket-mcp-server-java` all build; `/app` contains `<module>-1.0.0.jar`.

Plan: ticks C1.

### `prod/c2-show-answers` (base: `prod/c1-dockerfile-jar-glob`; merge C1 first, then retarget to `main`)
**PR title:** Show answers in the UI per asked question (C2 / T0.3)

**PR body:**
The UI called `ask()` but never displayed the answer (finding G1).

- New `AnswerList` component (`ui/src/components/AnswerList.tsx`) in an "Answers for <customer>" card below the question picker.
- One entry per question in the batch with a state badge (Waiting / Asking… / Answered / Failed), the answer text (multi-line preserved), or that question's error.
- Each new batch replaces the previous results; the history table is unchanged.
- Light and dark theme via the existing CSS tokens.

Stacked on C1 only because both tick adjacent checkboxes in `PRODUCTION_READINESS_PLAN.md` (otherwise the second merge conflicts). No code dependency on C1.

Verified: `cd ui && npm ci && npm run build` (type check + build). Automated UI tests arrive with C3.

Plan: ticks C2. Also updates `handoff/stream-c.md`.

## In progress
none

## Next task
C3 – dependencies satisfied: yes (best done after C2 is merged, since it adds tests for the answer display)

## Blocked on
nothing

## Notes for the next session
- Plan checkboxes for consecutive tasks sit on adjacent lines, so ticking them on separate branches off `main` conflicts. Either stack the branch on the previous unmerged task branch (as done for C2) or branch from `main` after the previous PR is merged.
- C2 was verified only by `npm run build`; it was not clicked through in a browser against a running assistant. C3 should add Testing Library tests for: answered state, failed state with the error text, and the waiting/asking states.
- Docker is available locally; the Dockerfile builds take a few minutes the first time (dependency:go-offline).
- Ports 5173 and 8080 were in use by the user's own processes; use other ports for any local dev servers.
