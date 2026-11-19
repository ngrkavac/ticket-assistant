# Figma split layout plan

Restyle the web UI to match the Figma frame "Question history · Split layout"
(file `6Z2LU4egPy2tAIGWnPBWff`, node `9:2`). No behaviour or API changes.

## Layout

- Page: background `#f8f9fb`, padding 40px 48px, full width (no 960px cap).
- Heading (title 30/40 semibold, subtitle 16/24 muted), 44px above the workspace.
- Two-column workspace, equal columns, 48px apart with a 1px vertical divider in the middle.
  - Left: Customer picker, Questions (checklist + Ask button + status hint).
  - Right: New question form, History table.
  - Sections inside a column are 32px apart.
- The white "tile" cards are removed; sections sit on the page background.
- Below 1024px the columns stack and the divider is hidden; the existing 640px mobile rules stay.

## Tokens (in `ui/src/index.css` `:root`)

| Token | Value |
|---|---|
| page | `#f8f9fb` |
| text | `#1d2736` |
| text-muted | `#68758e` |
| placeholder | `#80858e` |
| border | `#dde2ec` |
| checkbox border | `#88909c` |
| table head | `#f8f9fc` |
| badge bg | `#edf0f5` |
| primary | `#345ce5` (unchanged; design shows it disabled at 60% opacity) |
| control / card radius | 10px |
| control height | 46px |

## Components

- **CustomerPicker**: label 16/24 medium, 380px select, 46px tall.
- **QuestionPicker**: "Questions" title 18/24 semibold; one bordered rounded box holding all rows,
  1px dividers between rows, rows padded 16px with 12px gap; custom 16px checkbox
  (`#88909c` border, 3px radius, primary fill + check when ticked). Ask button min 80px wide.
- **NewQuestionForm**: "New question" title 18/24 semibold; input stretches, "Add to list" 116px
  secondary button (white, `#dde2ec` border).
- **HistoryTable**: heading 18/24 with 24px pill badge; table head 44px `#f8f9fc`; rows min 78px,
  20px padding, columns `60px | 453fr | 318fr`, question text regular weight.
- Hints: 13/20 muted.

States not in the design (hover, focus, checked, loading, errors, empty history) keep current
behaviour, restyled with the new tokens.
