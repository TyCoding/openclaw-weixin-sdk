# openclaw-weixin-sdk

LangChat Team 维护的腾讯 OpenClaw Weixin Java 协议 SDK。

- Maven 坐标：`cn.langchat.openclaw:openclaw-weixin-sdk:0.1.0-SNAPSHOT`
- 运行环境：`JDK 17+`
- 设计原则：以协议传输为核心，依赖尽量收敛，协议状态本地持久化

## SDK 覆盖范围

- 核心 HTTP 接口：`getupdates`、`sendmessage`、`getuploadurl`、`getconfig`、`sendtyping`
- 二维码登录链路：`get_bot_qrcode`、`get_qrcode_status`、轮询超时与刷新处理
- 长轮询监听与会话守卫（`errcode=-14` 冷却）
- `context_token` 与同步游标持久化
- CDN 媒体上传/下载与 AES-128-ECB 加解密辅助
- 通过 `sendTextStream` 提供分片流式发送能力

## 传输架构（ASCII）

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
                                | 默认: ~/.openclaw/openclaw-weixin    |
                                +-------------------------------------+
```

## 状态与持久化说明

SDK 不是纯无状态客户端，会落盘协议运行所需状态，用于断线恢复与上下文连续性：

- 账号信息与账号索引
- 会话对象维度的 `context_token`
- 账号维度的 `get_updates_buf` 同步游标

默认状态目录：

- `~/.openclaw/openclaw-weixin`
- 可通过环境变量覆盖：`OPENCLAW_STATE_DIR`（兼容变量 `CLAWDBOT_STATE_DIR`）

## 快速开始

```java
import cn.langchat.openclaw.weixin.OpenClawWeixinSdk;
import cn.langchat.openclaw.weixin.api.WeixinClientConfig;

OpenClawWeixinSdk sdk = new OpenClawWeixinSdk(
    WeixinClientConfig.builder()
        .baseUrl("https://ilinkai.weixin.qq.com")
        .build()
);

var session = sdk.qrFlow().start(null, null, false);
System.out.println("二维码链接: " + session.qrcodeUrl());

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

## 事件监听（链式调用）

```java
String accountId = result.accountId();

var stream = sdk.monitorStream(accountId)
    .onStart(() -> System.out.println("monitor started"))
    .onMessage(msg -> System.out.println("收到消息: " + msg.textBody()))
    .onEvent(event -> {
        if (event.hasMedia()) {
            System.out.println("媒体已保存: " + event.localMediaPath());
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

// 后续停止:
stream.stop().awaitStop(java.time.Duration.ofSeconds(3));
```

说明：

- 本 SDK 的 `sendTextStream(...)` 是“分片发送文本”，不是 LLM token 级回调。
- OpenClaw Weixin 协议本身不提供 `onPartialToolCall` 这类模型/工具执行事件。

## Preview

![iShot_2026-04-21_15.27.29](http://cdn.langchat.cn/langchat/imgs/20260421152817512.png)

![iShot_2026-04-21_15.26.47](http://cdn.langchat.cn/langchat/imgs/20260421152827745.png)

![iShot_2026-04-21_15.27.01](http://cdn.langchat.cn/langchat/imgs/20260421152834152.png)


## 构建

```bash
mvn -q -DskipTests compile
```

## 项目边界

- SDK 只负责协议传输、登录流程、监听与媒体链路。
- SDK 模块不再包含终端 CLI/TUI 入口代码，也不放 shell 启动脚本。
- 终端交互由 仓库独立 CLI 项目提供：`../openclaw-weixin-cli`。
- 使用示例与测试由模块提供：`../openclaw-weixin-examples`。

## 边界约束

- 业务层的会话历史、知识存储、工作流编排应由上层应用实现。
