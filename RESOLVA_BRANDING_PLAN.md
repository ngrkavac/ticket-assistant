# Resolva branding plan

Make the web UI homepage look like a finished product: rebrand it as **Resolva**, add a logo, header,
hero with the mission, feature highlights, a footer, dark mode and a favicon. The existing ask/history
workspace keeps its behaviour and API calls; it moves below the hero.

## Decisions (from the user)

| Topic | Choice |
|---|---|
| Name | Resolva |
| Layout | Sticky header + hero (mission + feature cards) + existing workspace, one page |
| Colour | Deep blue / indigo accent, slate neutrals |
| Audience | Support agents |
| Extras | Feature highlights, footer, dark mode (system default + header toggle), favicon |

## Brand

- **Logo**: a rounded speech bubble filled with an indigo gradient (`#6366f1` → `#4338ca`) holding a
  white check mark: "a question, resolved". Inline SVG component `Logo.tsx`, same artwork in
  `ui/public/favicon.svg`.
- **Wordmark**: "Resolva" in Inter 700, letter-spacing -0.02em, next to the mark.
- **Tagline**: "Helpdesk answers, resolved."

## Copy

- Eyebrow: "AI helpdesk assistant for support teams"
- Headline: "Resolve customer IT questions in seconds."
- Mission: "Resolva gives support agents one place to answer customer questions. It combines your
  team's knowledge base with live ticket data, and runs on a private local model, so customer data
  never leaves your network."
- CTAs: "Start asking" (scrolls to the workspace), "How it works" (scrolls to the feature cards).
- Feature cards:
  1. **Knowledge base answers**: step-by-step fixes drawn from your own runbooks (RAG).
  2. **Live ticket context**: checks open tickets and status for the selected customer (MCP).
  3. **Private by design**: a local Ollama model; no data sent to third-party AI APIs.
- Workspace heading: "Workspace" + the old subtitle ("Choose a customer, pick one or more questions…").
- Footer: logo + tagline on the left; "Built with Spring AI, MCP and Ollama" and the year on the right.

## Theme tokens (`ui/src/index.css`)

- Light: primary `#4f46e5` (indigo-600), hover `#4338ca`, primary-soft `#eef2ff` (indigo-50) for
  the eyebrow pill and feature icons; page background slate-50, cards white.
- Dark: page `#0b1120`, surface `#0f172a`, muted surface `#1e293b`, border `#1e293b`, input border
  `#334155`, text slate-50/300/400, primary `#6366f1`, primary-soft `rgba(99,102,241,0.15)`.
- Dark tokens apply under `@media (prefers-color-scheme: dark)` for `:root:not([data-theme="light"])`
  and always for `:root[data-theme="dark"]`.
- Hard-coded colours that break in dark mode (switch thumb, table header) use tokens or explicit white.
- The table header becomes the muted surface with secondary text instead of solid black, which suits
  the indigo accent better.

## Theme toggle

- `ThemeToggle.tsx` in the header: a sun/moon icon button. It reads the current effective theme
  (stored choice, else `prefers-color-scheme`), flips it, sets `data-theme` on `<html>` and stores it
  in `localStorage` (wrapped in try/catch).
- A tiny inline script in `index.html` applies the stored theme before React renders, so there is no
  flash of the wrong theme.

## Files

- `ui/index.html`: title "Resolva · Helpdesk Assistant", favicon link, meta description,
  theme-colour meta, pre-render theme script, Inter 700/800 weights.
- `ui/public/favicon.svg`: new.
- `ui/src/components/Logo.tsx`, `SiteHeader.tsx`, `Hero.tsx`, `SiteFooter.tsx`, `ThemeToggle.tsx`: new.
- `ui/src/App.tsx`: render header, hero, workspace section (with `id="workspace"`) and footer.
  State and handlers are unchanged.
- `ui/src/index.css`: tokens, dark mode, header/hero/feature/footer styles, responsive rules.

## Verification

- `npm run build` (tsc + vite) passes.
- Visual check in the dev server, light and dark, at desktop and phone widths.
