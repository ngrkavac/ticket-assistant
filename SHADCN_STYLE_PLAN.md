# shadcn/ui style plan

Restyle the web UI using the visual language of the Figma community template
"@shadcn/ui – Design System" (file `ar8NjQswRJOfMs5GbdwsNi`, cover node `13:2560`).
CSS only: no markup, behaviour or API changes. The split two-column layout from
`FIGMA_SPLIT_LAYOUT_PLAN.md` stays; only the look changes.

## Tokens (in `ui/src/index.css` `:root`)

| Token | Value | Figma style |
|---|---|---|
| page | `#ffffff` | — |
| text / primary | `#0f172a` | slate/900 |
| text-secondary | `#334155` | slate/700 |
| text-muted | `#64748b` | slate/500 |
| placeholder | `#94a3b8` | slate/400 |
| input border | `#cbd5e1` | slate/300 (input field) |
| border | `#e2e8f0` | slate/200 |
| muted surface | `#f1f5f9` | slate/100 (tabs, badge) |
| error | `#ef4444` | shadcn destructive |
| radius | 6px (tab items 3px) | |
| control height | 40px (8px vertical padding, 12px/16px horizontal) | |
| card shadow | `0 4px 6px rgba(0,0,0,0.09)` | shadow |

## Typography (Inter)

- Page title: 48/60 extra-bold, letter-spacing -0.576px (cover heading); 36/44 below 640px.
- Subtitle: 16/28 regular, muted (`p`).
- Section titles: 18/28 semibold (`large`).
- Labels: 14/20 medium (`subtle-medium`).
- Body / inputs: 14/20 regular (`subtle`).
- Hints: 14/20 regular, muted; badges / small meta: 12/20 medium (`detail`).

## Components

- **Cards**: each section (Customer, Questions, New question, History) is a white card with a slate-200
  border, 6px radius, the template's `shadow` effect and 24px padding (16px on phones). The page background is
  slate-50 (`#f8fafc`) so the cards stand out. The vertical divider is dropped and the 48px gap separates the columns.
- **Controls** (inputs, select, buttons) get shadcn's subtle `0 1px 2px rgba(0,0,0,0.05)` shadow.

- **Buttons**: primary is solid black (`#000`) with white 14/24 medium text, 16px horizontal padding and
  6px radius. It stays black even when disabled. Secondary is white with a slate-200 border and slate-900 text, hover slate-100.
  Disabled is 50% opacity.
- **Inputs / select**: 40px, white, 1px slate-300 border, 6px radius, 12px padding, slate-400 placeholder.
- **Focus**: shadcn ring, `0 0 0 2px #fff, 0 0 0 4px #94a3b8` (ring-offset-2, ring slate-400).
- **Question checklist**: a card (slate-200 border, 6px radius). Rows 12px/16px padding with slate-200 dividers
  and hover slate-100/50. Checkbox 16px, slate-900 border, 4px radius, slate-900 fill with a white check when ticked.
- **History**: the badge is a secondary pill (slate-100 bg, slate-900 text, 12px semibold). The table is a card
  with a 48px black header in 14px medium white text, row dividers, row hover slate-100/50 and 16px horizontal padding.
- **Divider** between columns: slate-200.

States not shown in the template (loading, errors, empty history) keep their current behaviour,
restyled with the new tokens.
