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
