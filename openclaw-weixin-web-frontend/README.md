# openclaw-weixin-web-frontend

Vue 3 + TypeScript + Tailwind + xterm.js frontend for scheme-1 web console mode.

## Structure

- `src/components/TerminalViewport.vue`: xterm terminal component (input/output + resize + ws bridge)
- `src/components/SessionSidebar.vue`: session list and operations
- `src/components/EventPanel.vue`: event stream panel
- `src/App.vue`: regional layout and state orchestration

## Run

```bash
cd openclaw-weixin-web-frontend
npm install
npm run dev
```

## Build

```bash
npm run typecheck
npm run build
```

Default dev URL: `http://localhost:15173`

Proxy defaults:

- `/api` -> `http://localhost:18080`
- `/ws` -> `ws://localhost:18080`
