# openclaw-weixin-sdk

LangChat Team 维护的微信 OpenClaw Java SDK 仓库（SDK + CLI + Examples + Web Terminal）。

English version: [README.en.md](./README.en.md)

## 1. 这是什么

本仓库围绕 **微信 OpenClaw 插件（iLinkAI 协议）** 构建，目标是：

- 用 Java（JDK 17+）实现可复用的协议 SDK
- 提供独立 CLI/TUI 工具用于实战接入
- 提供 examples 模块用于快速上手与测试
- 提供前后端分离的 Web Terminal 方案（Spring Boot + Vue3 + xterm.js）

## 2. 目录说明

```text
openclaw-weixin-sdk/
├─ README.md                           # 中文主文档
├─ README.en.md                        # English documentation
├─ pom.xml                             # 父工程（聚合构建）
├─ bin/
│  ├─ openclaw-weixin                  # CLI 启动脚本
│  └─ publish-openclaw-weixin-sdk      # Maven Central 自动化发布脚本
├─ openclaw-weixin-sdk/                # SDK 模块（发布到 Maven）
│  ├─ README.md
│  ├─ README.zh-CN.md
│  └─ docs/
├─ openclaw-weixin-cli/                # 独立 TUI CLI 项目
│  ├─ README.md
│  └─ README.zh-CN.md
├─ openclaw-weixin-examples/           # SDK 示例与测试
├─ openclaw-weixin-web-backend/        # Web Terminal 后端（Spring Boot 3）
└─ openclaw-weixin-web-frontend/       # Web Terminal 前端（Vue3 + Tailwind + xterm.js）
   ├─ README.md
   └─ src/*
```

## 3. 微信 OpenClaw 插件与 iLinkAI 协议

### 3.1 协议定位

该 SDK 对接的是微信 OpenClaw 插件相关的 iLinkAI HTTP 协议能力，核心是：

- 登录：二维码申请与状态轮询
- 收消息：`getupdates` 长轮询
- 发消息：`sendmessage`
- 输入中状态：`sendtyping`
- 媒体链路：`getuploadurl` + CDN 上传/下载
- 协议配置：`getconfig`

### 3.2 关键端点（iLinkAI）

```text
https://ilinkai.weixin.qq.com/ilink/bot/get_bot_qrcode
https://ilinkai.weixin.qq.com/ilink/bot/get_qrcode_status
https://ilinkai.weixin.qq.com/ilink/bot/getupdates
https://ilinkai.weixin.qq.com/ilink/bot/sendmessage
https://ilinkai.weixin.qq.com/ilink/bot/sendtyping
https://ilinkai.weixin.qq.com/ilink/bot/getuploadurl
https://ilinkai.weixin.qq.com/ilink/bot/getconfig
```

### 3.3 SDK 与 iLinkAI 运行架构（ASCII）

```text
+------------------------------------------------------------------------------------+
| 应用层（Agent / CLI / 业务服务）                                                    |
| 调用: login / sendText / sendTyping / monitorStream                                |
+-----------------------------------------------+------------------------------------+
                                                |
                                                v
+------------------------------------------------------------------------------------+
| OpenClawWeixinSdk（统一门面）                                                       |
|------------------------------------------------------------------------------------|
| 认证: QrLoginFlowService                                                           |
| 通信: WeixinApiClient (JDK HttpClient)                                             |
| 监听: WeixinLongPollMonitor + WeixinSessionGuard                                   |
| 媒体: MediaUploadService + CdnUploader/CdnMediaDownloader                          |
| 状态: FileAccountStore / FileContextTokenStore / FileSyncCursorStore               |
+-------------------------------+-------------------------------+--------------------+
                                |                               |
                                | HTTPS(JSON)                   | 本地持久化
                                v                               v
+--------------------------------------------------+    +--------------------------+
| Weixin OpenClaw Plugin (iLinkAI endpoints)       |    | ~/.openclaw/openclaw-   |
| /getupdates /sendmessage /sendtyping /getconfig  |    | weixin/*                |
| /get_bot_qrcode /get_qrcode_status /getuploadurl |    | - account               |
|                                                  |    | - context_token         |
|                                                  |    | - get_updates_buf       |
+--------------------------------------------------+    +--------------------------+
```

### 3.4 长轮询机制时序（ASCII）

```text
App/CLI            OpenClawWeixinSdk       WeixinLongPollMonitor           iLinkAI
   |                       |                         |                        |
   | monitorStream.start() |                         |                        |
   |---------------------->| createMonitorWithMedia  |                        |
   |                       |------------------------>| load cursor/context     |
   |                       |                         | from File*Store         |
   |                       |                         |                        |
   |                       |                         |==== LOOP ============== |
   |                       |                         | POST /getupdates        |
   |                       |                         | (get_updates_buf,       |
   |                       |                         |  longpoll timeout) ---> |
   |                       |                         | <--- msgs +             |
   |                       |                         |      get_updates_buf +  |
   |                       |                         |      longpolling_timeout|
   |                       |                         | save get_updates_buf     |
   |                       |                         | save context_token       |
   | <---- onMessage/event-|<------------------------| emit callbacks          |
   |                       |                         |========================= |
```

### 3.5 失败恢复与退避策略（与实现一致）

- `getupdates` 返回 `errcode=-14`（会话过期）时，`WeixinSessionGuard` 对该 `accountId` 进入暂停窗口（默认 1 小时），期间阻止继续发送与轮询。
- 其他失败按 `2s` 快速重试；连续失败达到 3 次后进入 `30s` 退避。
- 当 `getupdates` 发生 HTTP 超时，SDK 视为“本轮无新消息”，不会中断循环。
- 服务端若返回 `longpolling_timeout_ms`，SDK 会动态调整下一次轮询超时。
- 每条入站消息都会尝试更新 `context_token`；用于后续 `sendmessage/sendtyping` 保持对话上下文连续性。

## 4. 如何使用 SDK

### 4.1 Maven 依赖

```xml
<dependency>
  <groupId>cn.langchat.openclaw</groupId>
  <artifactId>openclaw-weixin-sdk</artifactId>
  <version>0.1.0</version>
</dependency>
```

### 4.2 Java 快速示例

```java
import cn.langchat.openclaw.weixin.OpenClawWeixinSdk;
import cn.langchat.openclaw.weixin.api.WeixinClientConfig;

public class QuickStart {
    public static void main(String[] args) {
        var sdk = new OpenClawWeixinSdk(
            WeixinClientConfig.builder()
                .baseUrl("https://ilinkai.weixin.qq.com")
                .build()
        );

        var session = sdk.qrFlow().start(null, null, false);
        System.out.println("二维码链接: " + session.qrcodeUrl());

        var login = sdk.qrFlow().waitForConfirm(
            session.sessionKey(),
            java.time.Duration.ofMinutes(8),
            null
        );

        if (!login.connected()) {
            return;
        }

        String accountId = login.accountId();
        String peer = "<userId@im.wechat>";

        sdk.sendText(accountId, peer, "Hello OpenClaw Weixin from Java");

        var stream = sdk.monitorStream(accountId)
            .onMessage(m -> System.out.println("收到: " + m.textBody()))
            .onError(Throwable::printStackTrace)
            .startAsync();

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
            stream.stop().awaitStop(java.time.Duration.ofSeconds(3))
        ));
    }
}
```

## 5. CLI 快速启动

```bash
./bin/openclaw-weixin chat
./bin/openclaw-weixin rebuild
```

## 6. 构建与测试

```bash
# SDK
mvn -q -f openclaw-weixin-sdk/pom.xml -DskipTests compile

# CLI
mvn -q -f openclaw-weixin-cli/pom.xml -DskipTests package

# Examples
mvn -q -f openclaw-weixin-examples/pom.xml test

# Web Backend
mvn -q -f openclaw-weixin-web-backend/pom.xml -DskipTests package
```

## 7. Web Terminal（方案1）

先启动后端：

```bash
mvn -pl openclaw-weixin-web-backend spring-boot:run
```

再启动前端：

```bash
cd openclaw-weixin-web-frontend
npm install
npm run dev
```

## 8. Preview

![iShot_2026-04-21_15.27.29](http://cdn.langchat.cn/langchat/imgs/20260421152817512.png)

![iShot_2026-04-21_15.26.47](http://cdn.langchat.cn/langchat/imgs/20260421152827745.png)

![iShot_2026-04-21_15.27.01](http://cdn.langchat.cn/langchat/imgs/20260421152834152.png)

## 9. 边界说明

- SDK 负责 iLinkAI 协议传输与协议状态维护（账号、`context_token`、`get_updates_buf`）。
- SDK 不负责业务历史消息存档与应用层编排。
- `sendTextStream(...)` 是分片发送能力，不是 LLM token 回调流。

## 10. 子模块文档

- SDK: `openclaw-weixin-sdk/README.md` / `openclaw-weixin-sdk/README.zh-CN.md`
- CLI: `openclaw-weixin-cli/README.md` / `openclaw-weixin-cli/README.zh-CN.md`
- Examples: `openclaw-weixin-examples/README.md` / `openclaw-weixin-examples/README.zh-CN.md`
- Web Backend: `openclaw-weixin-web-backend/README.md` / `openclaw-weixin-web-backend/README.zh-CN.md`
- Web Frontend: `openclaw-weixin-web-frontend/README.md` / `openclaw-weixin-web-frontend/README.zh-CN.md`
