# openclaw-weixin-web-backend

方案1（前后端分离 Web Terminal）后端：Spring Boot 3 + PTY + WebSocket。

职责：

- 启动 `openclaw-weixin` CLI 的 PTY 子进程
- 通过 WebSocket 桥接终端输出/输入
- 提供 REST 接口用于创建/销毁会话

## 接口

- `POST /api/terminal/sessions` 创建会话
- `GET /api/terminal/sessions` 查询会话
- `DELETE /api/terminal/sessions/{sessionId}` 关闭会话
- `WS /ws/terminal/{sessionId}` 终端流通道

WebSocket 消息格式：

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

## 启动

在仓库根目录执行：

```bash
mvn -pl openclaw-weixin-web-backend spring-boot:run
```

可配置项（`application.yml`）：

- `openclaw.web.terminal.workspace-dir`
- `openclaw.web.terminal.command`
- `openclaw.web.terminal.default-cols`
- `openclaw.web.terminal.default-rows`
