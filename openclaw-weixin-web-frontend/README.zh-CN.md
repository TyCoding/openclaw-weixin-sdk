# openclaw-weixin-web-frontend

方案1前端：Vue3 + TypeScript + Tailwind + xterm.js。

## 组件拆分

- `src/components/TerminalViewport.vue`：Terminal 组件封装（输入输出、窗口尺寸同步、WebSocket）
- `src/components/SessionSidebar.vue`：会话列表与控制区
- `src/components/EventPanel.vue`：事件日志区
- `src/App.vue`：分区域布局与状态编排

## 启动

```bash
cd openclaw-weixin-web-frontend
npm install
npm run dev
```

## 构建检查

```bash
npm run typecheck
npm run build
```

默认访问：`http://localhost:15173`

开发代理：

- `/api` -> `http://localhost:18080`
- `/ws` -> `ws://localhost:18080`
