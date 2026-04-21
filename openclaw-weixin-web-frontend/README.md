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

## Preview

**Web**

![iShot_2026-04-21_15.40.30](http://cdn.langchat.cn/langchat/imgs/20260421154038671.png)

**CLI**

![iShot_2026-04-21_15.27.29](http://cdn.langchat.cn/langchat/imgs/20260421152817512.png)

![iShot_2026-04-21_15.26.47](http://cdn.langchat.cn/langchat/imgs/20260421152827745.png)

![iShot_2026-04-21_15.27.01](http://cdn.langchat.cn/langchat/imgs/20260421152834152.png)

## Build

```bash
npm run typecheck
npm run build
```

Default dev URL: `http://localhost:15173`

Proxy defaults:

- `/api` -> `http://localhost:18080`
- `/ws` -> `ws://localhost:18080`
