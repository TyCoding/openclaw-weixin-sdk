# openclaw-weixin-cli

LangChat Team 实现的独立终端聊天工具，底层依赖 `openclaw-weixin-sdk`。

- 模块名：`openclaw-weixin-cli`
- 运行环境：`JDK 17+`
- UI 技术栈：TamboUI + JLine backend

## 项目定位

该项目与 SDK 拆分维护：

- SDK 负责协议传输与状态管理
- CLI 负责交互体验与终端界面
- CLI 既可独立发布，也可作为 SDK 示例工程

## 架构关系

```text
+-------------------------------+
| openclaw-weixin-cli   |
|-------------------------------|
| TUI 状态机                    |
| 账号选择 / 扫码登录            |
| 命令面板 / 底部输入框          |
| monitor 事件 -> 聊天区         |
+---------------+---------------+
                |
                v
+-------------------------------+
| openclaw-weixin-sdk           |
+-------------------------------+
                |
                v
+-------------------------------+
| Weixin OpenClaw HTTP APIs     |
+-------------------------------+
```

## 功能特性

- 全屏终端模式（alternate screen）
- 启动后在 TUI 内选择账号
- 在 TUI 内渲染二维码并扫码登录
- 中心区分栏：主对话区 + 侧边日志区
- 底部固定输入框
- 输入 `/` 自动展开命令建议
- 输入中状态联动（`sendtyping`）
- 后台 monitor 长轮询接收消息与媒体通知

## Preview

![iShot_2026-04-21_15.27.29](http://cdn.langchat.cn/langchat/imgs/20260421152817512.png)

![iShot_2026-04-21_15.26.47](http://cdn.langchat.cn/langchat/imgs/20260421152827745.png)

![iShot_2026-04-21_15.27.01](http://cdn.langchat.cn/langchat/imgs/20260421152834152.png)


## 构建

```bash
mvn -DskipTests -pl openclaw-weixin-cli -am package
```

构建产物：

- `target/openclaw-weixin-cli-<version>.jar`
- `target/openclaw-weixin-cli-<version>-all.jar`

## 运行

在仓库根目录执行：

```bash
./bin/openclaw-weixin chat
./bin/openclaw-weixin login
./bin/openclaw-weixin rebuild
./bin/openclaw-weixin help
```

或者直接运行 fat-jar：

```bash
java -jar target/openclaw-weixin-cli-0.1.0-all.jar chat
```

## 聊天命令

- `/help`
- `/users`
- `/to <userId@im.wechat>`
- `/media <path|url> [caption]`
- `/login`
- `/logout`
- `/clear`
- `/quit`

## 快捷键

- `Enter`：发送消息 / 确认当前操作
- `Ctrl+Enter`：追加一行换行输入
- `Esc`：清空当前输入
- `Ctrl+L`：清空聊天区和日志区
- `Ctrl+C`：退出

## 故障排查

- 如果没有会话对象，先执行 `/to <userId@im.wechat>`。
- 如果登录状态异常，先 `/logout` 再 `/login`。
- 如需隔离本地状态，可在启动前设置 `OPENCLAW_STATE_DIR`。
