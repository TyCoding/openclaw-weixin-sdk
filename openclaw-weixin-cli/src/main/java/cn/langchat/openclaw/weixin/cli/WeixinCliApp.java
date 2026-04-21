package cn.langchat.openclaw.weixin.cli;

import cn.langchat.openclaw.weixin.OpenClawWeixinSdk;
import cn.langchat.openclaw.weixin.api.WeixinClientConfig;
import cn.langchat.openclaw.weixin.auth.QrLoginFlowResult;
import cn.langchat.openclaw.weixin.auth.QrLoginSession;
import cn.langchat.openclaw.weixin.model.WeixinAccount;
import cn.langchat.openclaw.weixin.monitor.InboundMessageEvent;
import cn.langchat.openclaw.weixin.monitor.WeixinLongPollMonitor;
import cn.langchat.openclaw.weixin.storage.FileAccountStore;
import cn.langchat.openclaw.weixin.storage.OpenClawRouteTagLoader;
import cn.langchat.openclaw.weixin.storage.StateDirectoryResolver;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import dev.tamboui.css.engine.StyleEngine;
import dev.tamboui.layout.Constraint;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.StyledElement;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static dev.tamboui.toolkit.Toolkit.*;

/**
 * @since 2026-04-20
 * @author LangChat Team
 */
public final class WeixinCliApp {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int QR_QUIET_ZONE = 1;
    private static final String APP_TITLE = "LangChat OpenClaw Weixin";

    private static final Color CYAN = Color.rgb(120, 183, 255);
    private static final Color GREEN = Color.rgb(46, 204, 113);
    private static final Color YELLOW = Color.rgb(146, 181, 228);
    private static final Color RED = Color.rgb(231, 76, 60);
    private static final Color MAGENTA = Color.rgb(107, 154, 224);
    private static final Color DIM = Color.rgb(127, 140, 141);
    private static final Color BRIGHT = Color.rgb(236, 240, 241);
    private static final Color BUBBLE_OUT_BG = Color.rgb(74, 74, 74);
    private static final Color BUBBLE_OUT_FG = Color.rgb(241, 241, 241);
    private static final Color BUBBLE_IN_FG = Color.rgb(214, 214, 214);
    private static final Color PANEL_BORDER = Color.rgb(66, 78, 96);
    private static final Color PANEL_BORDER_FOCUS = Color.rgb(120, 183, 255);
    private static final Color HEADER_BORDER = Color.rgb(78, 88, 102);
    private static final Color LOG_BORDER = Color.rgb(72, 82, 95);
    private static final Color COMMAND_BORDER = Color.rgb(82, 100, 128);
    private static final Color INPUT_BORDER = Color.rgb(96, 126, 170);
    private static final int CHAT_WRAP_WIDTH = 72;
    private static final int INBOUND_SEPARATOR_WIDTH = 300;

    private static final List<SlashCommand> CHAT_COMMANDS = List.of(
        new SlashCommand("/help", "显示帮助"),
        new SlashCommand("/users", "查看已发现会话对象"),
        new SlashCommand("/to <userId@im.wechat>", "切换会话对象"),
        new SlashCommand("/media <path|url> [caption]", "发送媒体"),
        new SlashCommand("/login", "重新扫码登录"),
        new SlashCommand("/logout", "注销当前账号"),
        new SlashCommand("/clear", "清空聊天区"),
        new SlashCommand("/quit", "退出程序")
    );

    private final OpenClawWeixinCli.LaunchContext launch;
    private final FileAccountStore accountStore = new FileAccountStore();

    private final List<ChatBubble> chatLines = new ArrayList<>();
    private final List<UiLine> logLines = new ArrayList<>();
    private final Set<String> peers = ConcurrentHashMap.newKeySet();
    private final List<String> accountIds = new ArrayList<>();
    private final AtomicReference<String> currentPeer = new AtomicReference<>(null);
    private final TextInputState inputState = new TextInputState();
    private final List<String> chatDraftLines = new ArrayList<>();

    private final ConcurrentLinkedQueue<Runnable> uiQueue = new ConcurrentLinkedQueue<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(new DaemonThreadFactory("weixin-cli-io"));

    private final AtomicBoolean typingSent = new AtomicBoolean(false);
    private final AtomicBoolean typingInFlight = new AtomicBoolean(false);
    private final AtomicLong lastTypingAt = new AtomicLong(0L);
    private final AtomicBoolean loginInProgress = new AtomicBoolean(false);

    private volatile ToolkitRunner runner;
    private volatile WeixinLongPollMonitor monitor;
    private volatile Thread monitorThread;

    private volatile OpenClawWeixinSdk sdk;
    private volatile String accountId;
    private volatile WeixinAccount activeAccount;

    private volatile UiMode mode = UiMode.ACCOUNT_PICKER;
    private volatile boolean shuttingDown;
    private volatile String statusText = "初始化中";
    private volatile String qrUrl = "";
    private volatile List<String> qrLines = List.of();

    private volatile String lastMonitorMessage = "";
    private volatile long lastMonitorAt = 0L;
    private volatile int selectedAccountIndex = 0;

    public WeixinCliApp(OpenClawWeixinCli.LaunchContext launch) {
        this.launch = launch;
    }

    public void run() throws Exception {
        initStartupState();
        StyleEngine styleEngine = createStyleEngine();
        TuiConfig config = TuiConfig.builder()
            .alternateScreen(true)
            .mouseCapture(false)
            .tickRate(Duration.ofMillis(80))
            .build();

        try (ToolkitRunner created = ToolkitRunner.builder()
            .config(config)
            .styleEngine(styleEngine)
            .build()) {
            this.runner = created;
            created.run(this::render);
        } finally {
            shutdown();
        }
    }

    private void initStartupState() {
        refreshAccountIds();
        if (launch.forceNewLogin()) {
            beginQrLogin(launch.preferredAccountId());
            return;
        }

        if (!accountIds.isEmpty()) {
            mode = UiMode.ACCOUNT_PICKER;
            selectedAccountIndex = clampAccountIndex(findPreferredAccountIndex());
            statusText = "选择账号";
            return;
        }

        beginQrLogin(launch.preferredAccountId());
    }

    private void refreshAccountIds() {
        accountIds.clear();
        accountIds.addAll(accountStore.listAccountIds());
        accountIds.sort(Comparator.naturalOrder());
        selectedAccountIndex = clampAccountIndex(selectedAccountIndex);
    }

    private int findPreferredAccountIndex() {
        String preferred = launch.preferredAccountId();
        if (preferred == null || preferred.isBlank()) {
            return 0;
        }
        int idx = accountIds.indexOf(preferred);
        return idx >= 0 ? idx : 0;
    }

    private int clampAccountIndex(int idx) {
        if (accountIds.isEmpty()) {
            return 0;
        }
        if (idx < 0) {
            return 0;
        }
        if (idx >= accountIds.size()) {
            return accountIds.size() - 1;
        }
        return idx;
    }

    private StyleEngine createStyleEngine() throws IOException {
        StyleEngine engine = StyleEngine.create();
        engine.loadStylesheet("/styles/cli.tcss");
        return engine;
    }

    private Element render() {
        drainUiQueue();
        if (mode == UiMode.CHAT) {
            syncTypingState();
        } else {
            statusText = switch (mode) {
                case ACCOUNT_PICKER -> "选择账号";
                case QR_LOGIN -> loginInProgress.get() ? "等待扫码" : "可重试扫码";
                case CHAT -> statusText;
            };
        }

        String peerText = mode == UiMode.CHAT ? safe(currentPeer.get(), "(none)") : "-";
        String modeLabel = switch (mode) {
            case ACCOUNT_PICKER -> "account";
            case QR_LOGIN -> "login";
            case CHAT -> "chat";
        };

        List<SlashCommand> slashSuggestions = mode == UiMode.CHAT ? commandSuggestions(chatSuggestionInput()) : List.of();
        List<String> actionLines = buildActionLines(mode, slashSuggestions);
        boolean showCommandPanel = mode != UiMode.CHAT || !actionLines.isEmpty();
        Element inputEditor = textInput(inputState)
            .addClass("input-field")
            .placeholder(inputPlaceholderByMode())
            .showCursor(true)
            .cursorRequiresFocus(false)
            .onSubmit(this::submitInput)
            .constraint(Constraint.fill());

        Element centerArea = switch (mode) {
            case ACCOUNT_PICKER -> panel(() -> list()
                .data(buildAccountPickerLines(), UiLine::toElement)
                .displayOnly()
                .addClass("chat-list")
                .fill())
                .id("chat-panel")
                .addClass("chat-panel")
                .title("[ ACCOUNT ]")
                .rounded()
                .borderColor(PANEL_BORDER)
                .focusedBorderColor(PANEL_BORDER_FOCUS)
                .fill();
            case QR_LOGIN -> panel(() -> list()
                .data(buildQrLinesForUi(), UiLine::toElement)
                .displayOnly()
                .addClass("chat-list")
                .fill())
                .id("chat-panel")
                .addClass("chat-panel")
                .title("[ QR LOGIN ]")
                .rounded()
                .borderColor(PANEL_BORDER)
                .focusedBorderColor(PANEL_BORDER_FOCUS)
                .fill();
            case CHAT -> row(
                panel(() -> list()
                    .data(chatLines, ChatBubble::toElement)
                    .displayOnly()
                    .scrollToEnd()
                    .addClass("chat-main-list")
                    .fill())
                    .id("chat-main-panel")
                    .addClass("chat-main-panel")
                    .title("[ CHAT ]")
                    .rounded()
                    .borderColor(PANEL_BORDER)
                    .focusedBorderColor(PANEL_BORDER_FOCUS)
                    .fill(),
                panel(() -> list()
                    .data(logLines, UiLine::toElement)
                    .displayOnly()
                    .scrollToEnd()
                    .addClass("chat-log-list")
                    .fill())
                    .id("chat-log-panel")
                    .addClass("chat-log-panel")
                    .title("[ LOGS ]")
                    .rounded()
                    .borderColor(LOG_BORDER)
                    .focusedBorderColor(PANEL_BORDER_FOCUS)
                    .length(38)
            ).fill();
        };

        List<Element> layout = new ArrayList<>();

        layout.add(panel(() -> row(
                text(" " + APP_TITLE + " ").fg(BRIGHT).bold(),
                text("mode=" + modeLabel).fg(DIM),
                spacer(),
                text("peer=" + peerText).fg(CYAN),
                text("  [" + statusText + "] ").fg("正在输入中".equals(statusText) ? YELLOW : GREEN)
            ))
                .id("header-panel")
                .addClass("header-panel")
                .rounded()
                .borderColor(HEADER_BORDER)
                .length(3));

        layout.add(centerArea);

        if (showCommandPanel) {
            layout.add(panel(() -> list()
                .data(actionLines, line -> line.startsWith("/")
                    ? text(" " + line).fg(CYAN).bold()
                    : text(" " + line).fg(DIM))
                .displayOnly()
                .addClass("command-list")
                .fill())
                .id("command-panel")
                .addClass("command-panel")
                .title("[ COMMANDS ]")
                .rounded()
                .borderColor(COMMAND_BORDER)
                .focusedBorderColor(PANEL_BORDER_FOCUS)
                .length(4));
        }

        layout.add(panel(() -> row(
            text(" > ").fg(MAGENTA).bold(),
            inputEditor
        ).length(1))
            .id("input-panel")
            .addClass("input-panel")
            .title("[ INPUT ]")
            .doubleBorder()
            .borderColor(INPUT_BORDER)
            .focusedBorderColor(PANEL_BORDER_FOCUS)
            .length(3));

        return column(layout.toArray(Element[]::new))
            .id("main-root")
            .addClass("main-root")
            .focusable()
            .onKeyEvent(this::handleKey);
    }

    private EventResult handleKey(KeyEvent event) {
        if (event.matches(Actions.QUIT)) {
            quit();
            return EventResult.HANDLED;
        }

        if (event.code() == KeyCode.CHAR && event.hasCtrl() && event.character() == 'l') {
            if (mode == UiMode.CHAT) {
                chatLines.clear();
                logLines.clear();
                addSystem("已清空聊天与日志区。");
            }
            return EventResult.HANDLED;
        }

        if (mode == UiMode.ACCOUNT_PICKER && inputState.text().isBlank()) {
            if (event.isUp()) {
                selectedAccountIndex = clampAccountIndex(selectedAccountIndex - 1);
                return EventResult.HANDLED;
            }
            if (event.isDown()) {
                selectedAccountIndex = clampAccountIndex(selectedAccountIndex + 1);
                return EventResult.HANDLED;
            }
            if (event.matches(Actions.CONFIRM)) {
                selectCurrentAccount();
                return EventResult.HANDLED;
            }
            if (event.code() == KeyCode.CHAR && !event.hasCtrl() && !event.hasAlt()) {
                char c = Character.toLowerCase(event.character());
                if (c == 'n') {
                    beginQrLogin(currentSelectedAccountId());
                    return EventResult.HANDLED;
                }
                if (c == 'r') {
                    refreshAccountIds();
                    statusText = "已刷新账号列表";
                    return EventResult.HANDLED;
                }
            }
        }

        if (event.matches(Actions.CONFIRM)) {
            if (mode == UiMode.CHAT && event.hasCtrl()) {
                chatDraftLines.add(inputState.text());
                inputState.clear();
                return EventResult.HANDLED;
            }
            submitInput();
            return EventResult.HANDLED;
        }

        if (event.matches(Actions.CANCEL) && !currentInputText().isBlank()) {
            clearCurrentInput();
            return EventResult.HANDLED;
        }

        if (event.code() == KeyCode.CHAR && !event.hasCtrl() && !event.hasAlt()) {
            char ch = event.character();
            if (ch > 127 && !Character.isISOControl(ch)) {
                inputState.insert(ch);
                return EventResult.HANDLED;
            }
        }

        if (handleTextInputKey(inputState, event)) {
            return EventResult.HANDLED;
        }

        return EventResult.UNHANDLED;
    }

    private void submitInput() {
        switch (mode) {
            case ACCOUNT_PICKER -> {
                String line = inputState.text().trim();
                inputState.clear();
                submitAccountPickerInput(line);
            }
            case QR_LOGIN -> {
                String line = inputState.text().trim();
                inputState.clear();
                submitQrLoginInput(line);
            }
            case CHAT -> {
                String line = chatDraftText(inputState.text());
                chatDraftLines.clear();
                inputState.clear();
                submitChatInput(line);
            }
        }
    }

    private String currentInputText() {
        return mode == UiMode.CHAT ? chatDraftText(inputState.text()) : inputState.text();
    }

    private void clearCurrentInput() {
        if (mode == UiMode.CHAT) {
            chatDraftLines.clear();
            inputState.clear();
        } else {
            inputState.clear();
        }
    }

    private String chatSuggestionInput() {
        if (mode != UiMode.CHAT) {
            return "";
        }
        if (!chatDraftLines.isEmpty()) {
            return "";
        }
        return inputState.text();
    }

    private String chatDraftText(String currentLine) {
        if (chatDraftLines.isEmpty()) {
            return currentLine == null ? "" : currentLine;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : chatDraftLines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        if (currentLine != null && !currentLine.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(currentLine);
        }
        return sb.toString();
    }

    private void submitAccountPickerInput(String line) {
        if (line.isEmpty()) {
            selectCurrentAccount();
            return;
        }

        String lower = line.toLowerCase();
        if ("new".equals(lower) || "n".equals(lower)) {
            beginQrLogin(currentSelectedAccountId());
            return;
        }
        if ("refresh".equals(lower) || "r".equals(lower)) {
            refreshAccountIds();
            statusText = "已刷新账号列表";
            return;
        }

        try {
            int idx = Integer.parseInt(line);
            if (idx >= 1 && idx <= accountIds.size()) {
                selectedAccountIndex = idx - 1;
                selectCurrentAccount();
                return;
            }
        } catch (NumberFormatException ignore) {
            // ignore
        }

        int matched = accountIds.indexOf(line);
        if (matched >= 0) {
            selectedAccountIndex = matched;
            selectCurrentAccount();
            return;
        }

        statusText = "无效输入";
    }

    private void submitQrLoginInput(String line) {
        if (line.isEmpty()) {
            return;
        }
        String lower = line.toLowerCase();
        if ("cancel".equals(lower) || "back".equals(lower)) {
            refreshAccountIds();
            mode = UiMode.ACCOUNT_PICKER;
            statusText = "已取消扫码";
            return;
        }
        if ("regen".equals(lower) || "new".equals(lower) || "r".equals(lower)) {
            beginQrLogin(launch.preferredAccountId());
            return;
        }
        statusText = "输入 cancel 返回，输入 regen 重新生成";
    }

    private void submitChatInput(String line) {
        String normalized = line == null ? "" : line.strip();
        if (normalized.isEmpty()) {
            return;
        }

        if (normalized.startsWith("/") && !normalized.contains("\n")) {
            handleChatCommand(normalized);
            return;
        }

        OpenClawWeixinSdk currentSdk = sdk;
        String currentAccountId = accountId;
        String peer = currentPeer.get();

        if (currentSdk == null || currentAccountId == null || currentAccountId.isBlank()) {
            addWarn("SDK 未初始化，请重新登录。");
            return;
        }
        if (peer == null || peer.isBlank()) {
            addWarn("请先通过 /to 指定会话对象，或等待对方先发消息。");
            return;
        }

        addOut(normalized);
        ioExecutor.submit(() -> {
            try {
                String mid = currentSdk.sendText(currentAccountId, peer, normalized);
                uiQueue.offer(() -> addSystem("发送成功，messageId=" + mid));
            } catch (Exception ex) {
                uiQueue.offer(() -> addWarn("发送失败: " + ex.getMessage()));
            }
        });
    }

    private void selectCurrentAccount() {
        String selected = currentSelectedAccountId();
        if (selected == null) {
            beginQrLogin(launch.preferredAccountId());
            return;
        }

        WeixinAccount account = accountStore.load(selected).orElse(null);
        if (account == null || account.token() == null || account.token().isBlank()) {
            beginQrLogin(selected);
            return;
        }

        enterChatMode(account, true);
    }

    private String currentSelectedAccountId() {
        if (accountIds.isEmpty()) {
            return null;
        }
        selectedAccountIndex = clampAccountIndex(selectedAccountIndex);
        return accountIds.get(selectedAccountIndex);
    }

    private void beginQrLogin(String accountIdSeed) {
        if (!loginInProgress.compareAndSet(false, true)) {
            statusText = "扫码流程进行中";
            return;
        }

        mode = UiMode.QR_LOGIN;
        statusText = "正在生成二维码";
        qrUrl = "";
        qrLines = List.of(
            "二维码生成中，请稍候..."
        );

        ioExecutor.submit(() -> {
            try {
                OpenClawWeixinSdk loginSdk = new OpenClawWeixinSdk(
                    WeixinClientConfig.builder()
                        .baseUrl(launch.baseUrl())
                        .cdnBaseUrl(launch.cdnBaseUrl())
                        .build()
                );

                QrLoginSession session = loginSdk.qrFlow().start(accountIdSeed, null, false);
                List<String> renderedQr = renderQrToTerminal(session.qrcodeUrl());
                uiQueue.offer(() -> {
                    mode = UiMode.QR_LOGIN;
                    statusText = "请微信扫码并确认";
                    qrUrl = session.qrcodeUrl();
                    qrLines = renderedQr;
                });

                QrLoginFlowResult result = loginSdk.qrFlow().waitForConfirm(session.sessionKey(), Duration.ofMinutes(8), null);
                uiQueue.offer(() -> {
                    loginInProgress.set(false);
                    onQrLoginResult(result);
                });
            } catch (Exception ex) {
                uiQueue.offer(() -> {
                    loginInProgress.set(false);
                    mode = UiMode.QR_LOGIN;
                    statusText = "二维码流程失败";
                    qrLines = List.of("二维码流程失败: " + ex.getMessage());
                });
            }
        });
    }

    private void onQrLoginResult(QrLoginFlowResult result) {
        if (result.connected()) {
            WeixinAccount account = accountStore.load(result.accountId()).orElseGet(() -> new WeixinAccount(
                result.accountId(),
                result.botToken(),
                result.baseUrl(),
                result.userId(),
                java.time.Instant.now().toString()
            ));
            enterChatMode(account, false);
            return;
        }

        mode = UiMode.QR_LOGIN;
        statusText = "登录未完成";
        qrLines = List.of(
            "登录失败: " + safe(result.message(), "unknown"),
            "输入 regen 重新生成二维码，或输入 cancel 返回账号选择"
        );
    }

    private void enterChatMode(WeixinAccount account, boolean fromLocalAccount) {
        stopMonitor();
        sendTypingCancelIfNeeded();

        this.activeAccount = account;
        this.accountId = account.accountId();

        String effectiveBaseUrl = (account.baseUrl() == null || account.baseUrl().isBlank())
            ? launch.baseUrl()
            : account.baseUrl();
        String routeTag = OpenClawRouteTagLoader.loadRouteTag(resolveOpenClawConfigPath(), account.accountId()).orElse(null);

        this.sdk = new OpenClawWeixinSdk(
            WeixinClientConfig.builder()
                .baseUrl(effectiveBaseUrl)
                .cdnBaseUrl(launch.cdnBaseUrl())
                .token(account.token())
                .routeTag(routeTag)
                .build()
        );

        peers.clear();
        peers.addAll(this.sdk.listKnownPeers(account.accountId()));
        if (account.userId() != null && !account.userId().isBlank()) {
            peers.add(account.userId());
        }

        String initialPeer = launch.initialPeer();
        if ((initialPeer == null || initialPeer.isBlank()) && account.userId() != null && !account.userId().isBlank()) {
            initialPeer = account.userId();
        }
        currentPeer.set(initialPeer);

        chatLines.clear();
        logLines.clear();
        addSystem("LangChat Team 出品");
        addSystem((fromLocalAccount ? "已加载本地账号" : "扫码登录成功") + "，accountId=" + account.accountId());
        if (currentPeer.get() != null && !currentPeer.get().isBlank()) {
            addSystem("默认会话对象: " + currentPeer.get());
        }
        addSystem("输入 / 展开命令，Enter 发送，Ctrl+C 或 /quit 退出。");

        mode = UiMode.CHAT;
        statusText = "就绪";

        startMonitor();
    }

    private void startMonitor() {
        OpenClawWeixinSdk currentSdk = this.sdk;
        String currentAccountId = this.accountId;
        if (currentSdk == null || currentAccountId == null || currentAccountId.isBlank()) {
            return;
        }

        monitor = currentSdk.createMonitorWithMedia(
            currentAccountId,
            msg -> {
                // no-op
            },
            event -> uiQueue.offer(() -> onInboundEvent(event)),
            (level, message) -> {
                if (!"warn".equals(level) && !"error".equals(level)) {
                    return;
                }
                if (!shouldShowMonitorMessage(message)) {
                    return;
                }
                uiQueue.offer(() -> addWarn("[MONITOR] " + message));
            }
        );

        monitorThread = new Thread(() -> {
            try {
                monitor.runLoop();
            } catch (Exception ex) {
                uiQueue.offer(() -> addWarn("monitor 崩溃: " + ex.getMessage()));
            }
        }, "weixin-cli-monitor-" + currentAccountId);
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private void stopMonitor() {
        if (monitor != null) {
            monitor.stop();
        }
        if (monitorThread != null) {
            try {
                monitorThread.join(1200L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        monitor = null;
        monitorThread = null;
    }

    private boolean shouldShowMonitorMessage(String message) {
        long now = System.currentTimeMillis();
        String normalized = (message == null) ? "" : message;
        if (normalized.equals(lastMonitorMessage) && (now - lastMonitorAt) < 5000L) {
            return false;
        }
        lastMonitorMessage = normalized;
        lastMonitorAt = now;
        return true;
    }

    private void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;

        sendTypingCancelIfNeeded();
        stopMonitor();
        ioExecutor.shutdownNow();
    }

    private void handleChatCommand(String line) {
        if ("/quit".equals(line) || "/exit".equals(line)) {
            quit();
            return;
        }
        if ("/help".equals(line)) {
            for (SlashCommand cmd : CHAT_COMMANDS) {
                addSystem(cmd.syntax() + " - " + cmd.description());
            }
            return;
        }
        if ("/clear".equals(line)) {
            chatLines.clear();
            addSystem("已清空对话区。");
            return;
        }
        if ("/login".equals(line)) {
            reloginCurrentAccount();
            return;
        }
        if ("/logout".equals(line)) {
            logoutCurrentAccount();
            return;
        }
        if ("/users".equals(line)) {
            if (peers.isEmpty()) {
                addSystem("暂无会话对象。可先 /to <userId@im.wechat> 手动指定。");
            } else {
                peers.stream().sorted(Comparator.naturalOrder()).forEach(peer -> {
                    if (peer.equals(currentPeer.get())) {
                        addSystem("* " + peer + " (current)");
                    } else {
                        addSystem("* " + peer);
                    }
                });
            }
            return;
        }

        if ("/to".equals(line)) {
            addWarn("用法: /to <userId@im.wechat>");
            return;
        }
        if (line.startsWith("/to ")) {
            String nextPeer = line.substring(4).trim();
            if (nextPeer.isBlank()) {
                addWarn("用法: /to <userId@im.wechat>");
                return;
            }
            sendTypingCancelIfNeeded();
            currentPeer.set(nextPeer);
            peers.add(nextPeer);
            addSystem("当前会话对象: " + nextPeer);
            return;
        }

        if (line.startsWith("/media ")) {
            String[] parts = line.split("\\s+", 3);
            if (parts.length < 2) {
                addWarn("用法: /media <path|url> [caption]");
                return;
            }
            String peer = currentPeer.get();
            if (peer == null || peer.isBlank()) {
                addWarn("请先通过 /to 指定会话对象，或等待对方先发消息。");
                return;
            }

            OpenClawWeixinSdk currentSdk = sdk;
            String currentAccountId = accountId;
            if (currentSdk == null || currentAccountId == null || currentAccountId.isBlank()) {
                addWarn("SDK 未初始化，请重新登录。");
                return;
            }

            String media = parts[1];
            String caption = parts.length >= 3 ? parts[2] : "";
            addOut("[媒体] " + media + (caption.isBlank() ? "" : "\n说明: " + caption));
            ioExecutor.submit(() -> {
                try {
                    String mid = currentSdk.sendMedia(currentAccountId, peer, media, caption);
                    uiQueue.offer(() -> addSystem("媒体发送成功，messageId=" + mid));
                } catch (Exception ex) {
                    uiQueue.offer(() -> addWarn("发送媒体失败: " + ex.getMessage()));
                }
            });
            return;
        }

        addWarn("未知命令: " + line + "，输入 /help 查看可用命令。");
    }

    private void reloginCurrentAccount() {
        String seed = accountId;
        if (seed == null || seed.isBlank()) {
            seed = launch.preferredAccountId();
        }

        addSystem("已切换到扫码登录，确认后会进入新会话。");
        resetChatRuntimeState(false);
        beginQrLogin(seed);
    }

    private void logoutCurrentAccount() {
        String current = accountId;
        if (current == null || current.isBlank()) {
            addWarn("当前没有可注销账号。");
            return;
        }

        try {
            accountStore.clearAccount(current);
        } catch (Exception ex) {
            addWarn("注销失败: " + ex.getMessage());
            return;
        }

        addSystem("已注销账号: " + current);
        resetChatRuntimeState(true);
        refreshAccountIds();

        if (accountIds.isEmpty()) {
            beginQrLogin(launch.preferredAccountId());
            return;
        }

        mode = UiMode.ACCOUNT_PICKER;
        statusText = "请选择账号";
        selectedAccountIndex = clampAccountIndex(0);
    }

    private void resetChatRuntimeState(boolean clearPeerSelection) {
        sendTypingCancelIfNeeded();
        stopMonitor();

        this.sdk = null;
        this.accountId = null;
        this.activeAccount = null;
        typingSent.set(false);
        typingInFlight.set(false);
        lastTypingAt.set(0L);

        if (clearPeerSelection) {
            currentPeer.set(null);
        }
        peers.clear();
    }

    private void onInboundEvent(InboundMessageEvent event) {
        String body = safe(event.message().textBody(), "[non-text message]");

        String from = safe(event.message().fromUserId(), "(unknown)");
        peers.add(from);
        if (currentPeer.get() == null || currentPeer.get().isBlank()) {
            currentPeer.set(from);
        }

        addIn(body);
        if (event.hasMedia()) {
            addSystem("媒体已保存: " + event.localMediaPath() + " (" + safe(event.mediaType(), "unknown") + ")");
        }
    }

    private void syncTypingState() {
        OpenClawWeixinSdk currentSdk = sdk;
        String currentAccountId = accountId;
        String peer = currentPeer.get();

        if (currentSdk == null || currentAccountId == null || currentAccountId.isBlank()) {
            statusText = "SDK 未就绪";
            return;
        }

        String input = currentInputText().trim();
        long now = System.currentTimeMillis();

        if (input.isBlank() || peer == null || peer.isBlank()) {
            statusText = "就绪";
            if (typingSent.get()) {
                sendTypingAsync(peer, false);
            }
            return;
        }

        statusText = "正在输入中";
        if (!typingSent.get()) {
            sendTypingAsync(peer, true);
            return;
        }

        if ((now - lastTypingAt.get()) >= 4500L) {
            sendTypingAsync(peer, true);
        }
    }

    private void sendTypingCancelIfNeeded() {
        OpenClawWeixinSdk currentSdk = sdk;
        String currentAccountId = accountId;
        String peer = currentPeer.get();
        if (currentSdk == null || currentAccountId == null || currentAccountId.isBlank()) {
            return;
        }
        if (!typingSent.get() || peer == null || peer.isBlank()) {
            return;
        }
        try {
            currentSdk.sendTyping(currentAccountId, peer, false);
        } catch (Exception ignore) {
            // ignore
        } finally {
            typingSent.set(false);
        }
    }

    private void sendTypingAsync(String peer, boolean typing) {
        OpenClawWeixinSdk currentSdk = sdk;
        String currentAccountId = accountId;
        if (currentSdk == null || currentAccountId == null || currentAccountId.isBlank()) {
            return;
        }
        if (peer == null || peer.isBlank()) {
            return;
        }
        if (!typingInFlight.compareAndSet(false, true)) {
            return;
        }

        ioExecutor.submit(() -> {
            try {
                currentSdk.sendTyping(currentAccountId, peer, typing);
                if (typing) {
                    typingSent.set(true);
                    lastTypingAt.set(System.currentTimeMillis());
                } else {
                    typingSent.set(false);
                }
            } catch (Exception ex) {
                uiQueue.offer(() -> addWarn("typing 调用失败: " + ex.getMessage()));
                if (!typing) {
                    typingSent.set(false);
                }
            } finally {
                typingInFlight.set(false);
            }
        });
    }

    private void quit() {
        sendTypingCancelIfNeeded();
        ToolkitRunner current = runner;
        if (current != null) {
            current.quit();
        }
    }

    private void drainUiQueue() {
        for (int i = 0; i < 128; i++) {
            Runnable action = uiQueue.poll();
            if (action == null) {
                break;
            }
            action.run();
        }
    }

    private static List<SlashCommand> commandSuggestions(String input) {
        String text = input == null ? "" : input.trim();
        if (!text.startsWith("/")) {
            return List.of();
        }
        if ("/".equals(text)) {
            return CHAT_COMMANDS;
        }
        List<SlashCommand> out = new ArrayList<>();
        for (SlashCommand cmd : CHAT_COMMANDS) {
            if (cmd.syntax().startsWith(text)) {
                out.add(cmd);
            }
        }
        return out;
    }

    private List<UiLine> buildAccountPickerLines() {
        List<UiLine> out = new ArrayList<>();
        out.add(UiLine.bold("LangChat Team - 选择一个账号开始会话", CYAN));
        out.add(UiLine.of("", DIM));

        if (accountIds.isEmpty()) {
            out.add(UiLine.of("当前无本地账号，请输入 new 或直接回车进入扫码登录。", DIM));
            return out;
        }

        for (int i = 0; i < accountIds.size(); i++) {
            String aid = accountIds.get(i);
            boolean selected = i == clampAccountIndex(selectedAccountIndex);
            String line = (selected ? " > " : "   ") + (i + 1) + ". " + aid;
            out.add(selected ? UiLine.bold(line, BRIGHT) : UiLine.of(line, DIM));
        }
        return out;
    }

    private List<UiLine> buildQrLinesForUi() {
        List<UiLine> out = new ArrayList<>();
        out.add(UiLine.bold("LangChat 微信扫码登录", CYAN));
        out.add(UiLine.of("", DIM));

        if (qrLines.isEmpty()) {
            out.add(UiLine.of("二维码生成中...", DIM));
        } else {
            for (String s : qrLines) {
                out.add(UiLine.of(s, BRIGHT));
            }
        }

        if (qrUrl != null && !qrUrl.isBlank()) {
            out.add(UiLine.of("", DIM));
            out.add(UiLine.of("扫码链接: " + qrUrl, DIM));
        }
        return out;
    }

    private static List<String> buildActionLines(UiMode mode, List<SlashCommand> slashSuggestions) {
        return switch (mode) {
            case ACCOUNT_PICKER -> List.of(
                "↑/↓ 选择账号，Enter 确认",
                "输入序号直接选择",
                "输入 new 开始扫码登录",
                "输入 refresh 刷新账号列表"
            );
            case QR_LOGIN -> List.of(
                "等待微信扫码并确认",
                "输入 regen 重新生成二维码",
                "输入 cancel 返回账号选择"
            );
            case CHAT -> buildChatActionLines(slashSuggestions);
        };
    }

    private static List<String> buildChatActionLines(List<SlashCommand> suggestions) {
        if (suggestions.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int limit = Math.min(4, suggestions.size());
        for (int i = 0; i < limit; i++) {
            SlashCommand cmd = suggestions.get(i);
            out.add(cmd.syntax() + "  " + cmd.description());
        }
        return out;
    }

    private String inputPlaceholderByMode() {
        return switch (mode) {
            case ACCOUNT_PICKER -> "输入序号后回车选择，或输入 new / refresh";
            case QR_LOGIN -> "等待扫码自动登录；输入 regen 重试，输入 cancel 返回";
            case CHAT -> "输入消息，Enter发送，Ctrl+Enter换行，Esc清空，Ctrl+L清屏，Ctrl+C退出，输入 / 查看命令";
        };
    }

    private void addSystem(String content) {
        logLines.add(UiLine.of("[" + now() + "] " + content, DIM));
        trimLogLines();
    }

    private void addWarn(String content) {
        logLines.add(UiLine.bold("[" + now() + "] WARN  " + content, RED));
        trimLogLines();
    }

    private void addOut(String content) {
        appendChatMessage(true, content);
    }

    private void addIn(String content) {
        appendChatMessage(false, content);
    }

    private void appendChatMessage(boolean outbound, String content) {
        String normalized = content == null ? "" : content.strip();
        if (normalized.isBlank()) {
            normalized = "(空消息)";
        }

        List<String> wrapped = wrapChatLines(normalized, CHAT_WRAP_WIDTH);
        if (outbound) {
            int width = wrapped.stream().mapToInt(String::length).max().orElse(1) + 2;
            chatLines.add(ChatBubble.out("  " + " ".repeat(width)));
            for (String line : wrapped) {
                String payload = "> " + line;
                chatLines.add(ChatBubble.out("  " + padRight(payload, width)));
            }
            chatLines.add(ChatBubble.out("  " + " ".repeat(width)));
        } else {
            chatLines.add(ChatBubble.in("  "));
            for (String line : wrapped) {
                chatLines.add(ChatBubble.in("  " + line));
            }
            chatLines.add(ChatBubble.in("  "));
            chatLines.add(ChatBubble.inSeparator("─".repeat(INBOUND_SEPARATOR_WIDTH)));
        }
        trimChatLines();
    }

    private static List<String> wrapChatLines(String text, int width) {
        int max = Math.max(12, width);
        List<String> out = new ArrayList<>();
        String[] src = text.replace("\r\n", "\n").split("\n", -1);
        for (String line : src) {
            if (line.isEmpty()) {
                out.add(" ");
                continue;
            }
            int start = 0;
            while (start < line.length()) {
                int end = Math.min(line.length(), start + max);
                out.add(line.substring(start, end));
                start = end;
            }
        }
        return out;
    }

    private static String padRight(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }

    private void trimLogLines() {
        int max = 1200;
        if (logLines.size() <= max) {
            return;
        }
        logLines.subList(0, logLines.size() - max).clear();
    }

    private void trimChatLines() {
        int max = 800;
        if (chatLines.size() <= max) {
            return;
        }
        chatLines.subList(0, chatLines.size() - max).clear();
    }

    private static String now() {
        return LocalTime.now().format(TIME_FMT);
    }

    private static String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static Path resolveOpenClawConfigPath() {
        String fromEnv = System.getenv("OPENCLAW_CONFIG");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv);
        }
        return StateDirectoryResolver.resolveStateDir().resolve("openclaw.json");
    }

    private static List<String> renderQrToTerminal(String text) {
        if (text == null || text.isBlank()) {
            return List.of("二维码链接为空");
        }

        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            hints.put(EncodeHintType.MARGIN, QR_QUIET_ZONE);
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints);
            List<String> lines = new ArrayList<>();
            int w = matrix.getWidth();
            int h = matrix.getHeight();

            String sidePad = "";
            String horizontalPad = " ".repeat(w + sidePad.length() * 2);
            lines.add(horizontalPad);
            for (int y = 0; y < h; y += 2) {
                StringBuilder sb = new StringBuilder(w + sidePad.length() * 2);
                sb.append(sidePad);
                for (int x = 0; x < w; x++) {
                    boolean upper = matrix.get(x, y);
                    boolean lower = (y + 1 < h) && matrix.get(x, y + 1);
                    sb.append(qrHalfBlock(upper, lower));
                }
                sb.append(sidePad);
                lines.add(sb.toString());
            }
            lines.add(horizontalPad);
            return lines;
        } catch (Exception ex) {
            return List.of(
                "二维码渲染失败: " + ex.getMessage(),
                "请直接打开链接扫码"
            );
        }
    }

    private static char qrHalfBlock(boolean upper, boolean lower) {
        if (upper && lower) {
            return '█';
        }
        if (upper) {
            return '▀';
        }
        if (lower) {
            return '▄';
        }
        return ' ';
    }

    private enum UiMode {
        ACCOUNT_PICKER,
        QR_LOGIN,
        CHAT
    }

    /**
     * @since 2026-04-20
     * @author LangChat Team
     */
    private record SlashCommand(String syntax, String description) {
    }

    /**
     * @since 2026-04-20
     * @author LangChat Team
     */
    private record ChatBubble(String content, Color fg, Color bg) {
        static ChatBubble out(String content) {
            return new ChatBubble(content, BUBBLE_OUT_FG, BUBBLE_OUT_BG);
        }

        static ChatBubble in(String content) {
            return new ChatBubble(content, BUBBLE_IN_FG, null);
        }

        static ChatBubble inSeparator(String content) {
            return new ChatBubble(content, DIM, null);
        }

        StyledElement<?> toElement() {
            Style style = Style.EMPTY;
            if (fg != null) {
                style = style.fg(fg);
            }
            if (bg != null) {
                style = style.bg(bg);
            }
            return text(content).style(style);
        }
    }

    /**
     * @since 2026-04-20
     * @author LangChat Team
     */
    private record UiLine(String content, Color color, boolean bold) {
        static UiLine of(String content, Color color) {
            return new UiLine(content, color, false);
        }

        static UiLine bold(String content, Color color) {
            return new UiLine(content, color, true);
        }

        StyledElement<?> toElement() {
            Style style = Style.EMPTY.fg(color);
            if (bold) {
                style = style.bold();
            }
            return text(content).style(style);
        }
    }

    /**
     * @since 2026-04-20
     * @author LangChat Team
     */
    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicLong seq = new AtomicLong(0);

        private DaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
