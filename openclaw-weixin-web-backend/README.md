# openclaw-weixin-web-backend

Spring Boot 3 backend for Web Terminal mode (scheme 1):

- starts `openclaw-weixin` CLI in a PTY process
- bridges PTY output/input through WebSocket
- provides REST APIs to create/close/list terminal sessions

## Endpoints

- `POST /api/terminal/sessions` create terminal session
- `GET /api/terminal/sessions` list sessions
- `DELETE /api/terminal/sessions/{sessionId}` close session
- `WS /ws/terminal/{sessionId}` terminal stream

WebSocket message protocol:

```json
{"type":"input","data":"hello"}
{"type":"resize","cols":140,"rows":40}
```

## Preview

**Web**

![iShot_2026-04-21_15.40.30](http://cdn.langchat.cn/langchat/imgs/20260421154038671.png)

**CLI**

![iShot_2026-04-21_15.27.29](http://cdn.langchat.cn/langchat/imgs/20260421152817512.png)

![iShot_2026-04-21_15.26.47](http://cdn.langchat.cn/langchat/imgs/20260421152827745.png)

![iShot_2026-04-21_15.27.01](http://cdn.langchat.cn/langchat/imgs/20260421152834152.png)

## Run

From repository root:

```bash
mvn -pl openclaw-weixin-web-backend spring-boot:run
```

Config (`application.yml`):

- `openclaw.web.terminal.workspace-dir`
- `openclaw.web.terminal.command`
- `openclaw.web.terminal.default-cols`
- `openclaw.web.terminal.default-rows`
