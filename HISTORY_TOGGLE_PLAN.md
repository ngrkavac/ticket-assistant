# History toggle plan

Add a switch above the History card, styled like the "Airplane mode" switch in the shadcn/ui Figma template
(file `ar8NjQswRJOfMs5GbdwsNi`, node `9:337`). The history card is shown only while the switch is on.

## Behaviour
- Label: "Show history". It starts **on**, so the page opens as it does today.
- Off: the History card isn't rendered. On: it renders again with the current entries.
- History is still fetched while hidden (on customer change and after asking), so turning it back on shows
  fresh data straight away. No API changes.
- The state isn't persisted; a reload turns it back on.

## Implementation
- `ui/src/components/Switch.tsx` (new): a native `<input type="checkbox" role="switch">` inside a `<label>`, so
  clicking the label toggles it and the keyboard (Space) and screen readers work. Props: `id`, `label`,
  `checked`, `onChange`.
- `ui/src/App.tsx`: `const [showHistory, setShowHistory] = useState(true)`. Render `<Switch>` between
  `NewQuestionForm` and `HistoryTable`, and render `HistoryTable` only when `showHistory` is true.
- `ui/src/index.css`:
  - Track 44×24, fully rounded, slate-200 (`#e2e8f0`) when off and black (`--color-primary`) when on.
  - 20px white thumb with a small shadow that slides 20px when on (0.15s).
  - Label 14px medium, 8px from the track (template: `small` text style, 8px gap).
  - Focus ring on the track when the input has keyboard focus. Disabled at 50% opacity, not used for now.
  - The switch is excluded from the card styling that applies to every section in a column.

## Verification
- `cd ui && npm run build`, then `docker compose up --build -d ui` and check http://localhost:5173. The switch
  hides and shows History, works by clicking either the label or the track, and toggles with Space when focused.
