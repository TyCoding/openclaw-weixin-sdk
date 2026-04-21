# openclaw-weixin-sdk

Java SDK for Tencent OpenClaw Weixin protocol, maintained by LangChat Team.

- Maven: `cn.langchat.openclaw:openclaw-weixin-sdk:0.1.0-SNAPSHOT`
- Runtime: `JDK 17+`
- SDK policy: protocol-focused, low dependency footprint, local state persistence for account/session context

## What This SDK Covers

- Core HTTP APIs: `getupdates`, `sendmessage`, `getuploadurl`, `getconfig`, `sendtyping`
- QR login flow: `get_bot_qrcode`, `get_qrcode_status`, timeout polling, refresh handling
- Long-poll monitor with session guard (`errcode=-14` cooldown handling)
- Context token persistence and sync cursor persistence
- CDN media upload/download with AES-128-ECB helpers
- Chunk-based stream-like sending via `sendTextStream`

## Transport Architecture (ASCII)

```text
                               +----------------------------------+
                               | Weixin OpenClaw HTTP Endpoints   |
                               |----------------------------------|
                               | /ilink/bot/getupdates            |
                               | /ilink/bot/sendmessage           |
                               | /ilink/bot/getuploadurl          |
                               | /ilink/bot/getconfig             |
                               | /ilink/bot/sendtyping            |
                               | /ilink/bot/get_bot_qrcode        |
                               | /ilink/bot/get_qrcode_status     |
                               +-------------------+--------------+
                                                   ^
                                                   | HTTPS(JSON)
+----------------------+        +------------------+------------------+
| LangChat App / CLI   | <----> |        OpenClawWeixinSdk            |
+----------------------+        |-------------------------------------|
                                | WeixinApiClient / QrLoginClient     |
                                | MediaUploadService / CdnUploader    |
                                | WeixinLongPollMonitor               |
                                | WeixinSessionGuard                  |
                                +------------------+------------------+
                                                   |
                                                   v
                                +-------------------------------------+
                                | Local Persistent Stores             |
                                |-------------------------------------|
                                | FileAccountStore                    |
                                | FileContextTokenStore               |
                                | FileSyncCursorStore                 |
                                | default: ~/.openclaw/openclaw-weixin|
                                +-------------------------------------+
```

## State and Persistence

The SDK is not stateless. It persists protocol-related state to make reconnect and continuation reliable:

- account credentials and account index
- `context_token` per peer
- `get_updates_buf` sync cursor per account

Default state root:

- `~/.openclaw/openclaw-weixin`
- override by env: `OPENCLAW_STATE_DIR` (or compatibility env `CLAWDBOT_STATE_DIR`)

## Quick Start

```java
import cn.langchat.openclaw.weixin.OpenClawWeixinSdk;
import cn.langchat.openclaw.weixin.api.WeixinClientConfig;

OpenClawWeixinSdk sdk = new OpenClawWeixinSdk(
    WeixinClientConfig.builder()
        .baseUrl("https://ilinkai.weixin.qq.com")
        .build()
);

var session = sdk.qrFlow().start(null, null, false);
System.out.println("QR URL: " + session.qrcodeUrl());

var result = sdk.qrFlow().waitForConfirm(
    session.sessionKey(),
    java.time.Duration.ofMinutes(8),
    null
);

if (result.connected()) {
    String accountId = result.accountId();
    String peer = "<userId@im.wechat>";
    sdk.sendText(accountId, peer, "Hello from LangChat Java SDK");
}
```

## Event-Driven Monitor (Fluent)

```java
String accountId = result.accountId();

var stream = sdk.monitorStream(accountId)
    .onStart(() -> System.out.println("monitor started"))
    .onMessage(msg -> System.out.println("inbound: " + msg.textBody()))
    .onEvent(event -> {
        if (event.hasMedia()) {
            System.out.println("media saved: " + event.localMediaPath());
        }
    })
    .onLog((level, line) -> {
        if ("warn".equals(level) || "error".equals(level)) {
            System.out.println("[" + level + "] " + line);
        }
    })
    .onError(Throwable::printStackTrace)
    .onStop(() -> System.out.println("monitor stopped"))
    .startAsync();

// later:
stream.stop().awaitStop(java.time.Duration.ofSeconds(3));
```

Notes:

- `sendTextStream(...)` in this SDK means chunk-based outbound sending, not LLM token callbacks.
- OpenClaw Weixin protocol itself does not provide `onPartialToolCall`-style model/tool events.

## Preview

**Web**

![iShot_2026-04-21_15.40.30](http://cdn.langchat.cn/langchat/imgs/20260421154038671.png)

**CLI**

![iShot_2026-04-21_15.27.29](http://cdn.langchat.cn/langchat/imgs/20260421152817512.png)

![iShot_2026-04-21_15.26.47](http://cdn.langchat.cn/langchat/imgs/20260421152827745.png)

![iShot_2026-04-21_15.27.01](http://cdn.langchat.cn/langchat/imgs/20260421152834152.png)


## Build

```bash
mvn -q -DskipTests compile
```

## Project Boundaries

- This SDK focuses on protocol transport, login flow, monitor, and media pipeline.
- SDK module does not include terminal CLI/TUI entry code or shell launcher scripts.
- Terminal interaction is provided by repository CLI project: `../openclaw-weixin-cli`.
- Usage examples and tests are provided by: `../openclaw-weixin-examples`.

## Scope Boundary

- Product-level chat memory, business history, and workflow orchestration should stay in upper-layer applications.
