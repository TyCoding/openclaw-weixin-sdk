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
import java.util.LinkedHashMap;
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
    private static final Color CONTEXT_BORDER = Color.rgb(76, 94, 122);
    private static final Color COMMAND_BORDER = Color.rgb(82, 100, 128);
    private static final Color INPUT_BORDER = Color.rgb(96, 126, 170);
    private static final int CHAT_WRAP_WIDTH = 72;
    private static final int INBOUND_SEPARATOR_WIDTH = 300;
    private static final int CONVERSATION_MAX_MESSAGES = 200;
    private static final String LOCAL_CONTEXT_PREFIX = "ctx-";

    private static final List<SlashCommand> CHAT_COMMANDS = List.of(
        new SlashCommand("/help", "显示帮助"),
        new SlashCommand("/users", "查看已发现会话对象"),
        new SlashCommand("/new [窗口名]", "创建当前账号的本地上下文窗口"),
        new SlashCommand("/to <userId@im.wechat>", "设置当前窗口的发送目标(或切换目标会话)"),
        new SlashCommand("/ctx [序号|contextId]", "从上下文面板切换会话对象"),
        new SlashCommand("/media <path|url> [caption]", "发送媒体"),
        new SlashCommand("/login", "重新扫码登录"),
        new SlashCommand("/logout", "注销当前账号"),
        new SlashCommand("/clear", "清空聊天区"),
        new SlashCommand("/quit", "退出程序")
    );

    private final OpenClawWeixinCli.LaunchContext launch;
    private final FileAccountStore accountStore = new FileAccountStore();
    private final FileConversationStore conversationStore = new FileConversationStore();

    private final List<ChatBubble> chatLines = new ArrayList<>();
    private final List<UiLine> logLines = new ArrayList<>();
    private final Set<String> peers = ConcurrentHashMap.newKeySet();
    private final Map<String, List<ConversationMessage>> conversationMessages = new ConcurrentHashMap<>();
    private final Map<String, String> conversationTitle = new ConcurrentHashMap<>();
    private final Map<String, String> contextTargetPeer = new ConcurrentHashMap<>();
    private final Map<String, String> conversationPreview = new ConcurrentHashMap<>();
    private final Map<String, Integer> conversationUnread = new ConcurrentHashMap<>();
    private final Map<String, Long> conversationUpdatedAt = new ConcurrentHashMap<>();
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
    private final AtomicLong localContextSeq = new AtomicLong(0L);

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
    private volatile int selectedPeerIndex = 0;

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

        String peerText;
        if (mode == UiMode.CHAT) {
            String current = currentPeer.get();
            String target = resolveTargetPeer(current);
            peerText = (target == null || target.isBlank()) ? safe(current, "(none)") : target;
        } else {
            peerText = "-";
        }
        String modeLabel = switch (mode) {
            case ACCOUNT_PICKER -> "account";
            case QR_LOGIN -> "login";
            case CHAT -> "chat";
        };
        List<String> peerCandidates = mode == UiMode.CHAT ? sortedPeers() : List.of();
        selectedPeerIndex = clampPeerIndex(selectedPeerIndex, peerCandidates);

        List<SlashCommand> slashSuggestions = mode == UiMode.CHAT ? commandSuggestions(chatSuggestionInput()) : List.of();
        List<String> actionLines = buildActionLines(mode, slashSuggestions);
        boolean showCommandPanel = !actionLines.isEmpty();
        Element inputEditor = textInput(inputState)
            .addClass("input-field")
            .placeholder(inputPlaceholderByMode())
            .showCursor(true)
            .cursorRequiresFocus(false)
            .focusable(false)
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
                    .data(buildContextLines(peerCandidates), UiLine::toElement)
                    .displayOnly()
                    .addClass("context-list")
                    .fill())
                    .id("context-panel")
                    .addClass("context-panel")
                    .title("[ CONTEXT ]")
                    .rounded()
                    .borderColor(CONTEXT_BORDER)
                    .focusedBorderColor(PANEL_BORDER_FOCUS)
                    .length(30),
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
                .length(mode == UiMode.CHAT ? 6 : 4));
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

        if (mode == UiMode.CHAT && currentInputText().isBlank()) {
            List<String> candidates = sortedPeers();
            if (!candidates.isEmpty()) {
                if (event.isUp() || event.matches(Actions.MOVE_UP)) {
                    selectedPeerIndex = clampPeerIndex(selectedPeerIndex - 1, candidates);
                    return EventResult.HANDLED;
                }
                if (event.isDown() || event.matches(Actions.MOVE_DOWN)) {
                    selectedPeerIndex = clampPeerIndex(selectedPeerIndex + 1, candidates);
                    return EventResult.HANDLED;
                }
                if (event.matches(Actions.CONFIRM)) {
                    selectPeerByIndex(selectedPeerIndex, candidates);
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

        if ((normalized.startsWith("/") || normalized.startsWith(".")) && !normalized.contains("\n")) {
            handleChatCommand(normalizeSlashCommand(normalized));
            return;
        }

        OpenClawWeixinSdk currentSdk = sdk;
        String currentAccountId = accountId;
        String contextId = currentPeer.get();
        String targetPeer = resolveTargetPeer(contextId);

        if (currentSdk == null || currentAccountId == null || currentAccountId.isBlank()) {
            addWarn("SDK 未初始化，请重新登录。");
            return;
        }
        if (contextId == null || contextId.isBlank() || targetPeer == null || targetPeer.isBlank()) {
            addWarn("当前窗口未绑定发送对象，请先 /to <userId@im.wechat>。");
            return;
        }

        addOut(contextId, normalized);
        ioExecutor.submit(() -> {
            try {
                String mid = currentSdk.sendText(currentAccountId, targetPeer, normalized);
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
        loadPersistedConversations(account.accountId());
        primeConversationMetadata(peers);

        String initialPeer = launch.initialPeer();
        if ((initialPeer == null || initialPeer.isBlank()) && account.userId() != null && !account.userId().isBlank()) {
            initialPeer = account.userId();
        }
        currentPeer.set(initialPeer);
        alignPeerSelectionToCurrent();
        syncVisibleChatFromCurrentPeer();

        logLines.clear();
        addSystem("LangChat Team 出品");
        addSystem((fromLocalAccount ? "已加载本地账号" : "扫码登录成功") + "，accountId=" + account.accountId());
        if (currentPeer.get() != null && !currentPeer.get().isBlank()) {
            addSystem("默认会话对象: " + currentPeer.get());
        }
        addSystem("上下文切换: ↑/↓ 选择左侧会话，Enter 切换，/new 新建窗口，/to 绑定发送对象");
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
        persistConversations();
        stopMonitor();
        ioExecutor.shutdownNow();
    }

    private void handleChatCommand(String line) {
        line = normalizeSlashCommand(line);
        if ("/quit".equals(line) || "/exit".equals(line)) {
            quit();
            return;
        }
        if ("/help".equals(line)) {
            for (SlashCommand cmd : CHAT_COMMANDS) {
                addSystem(cmd.syntax() + " - " + cmd.description());
            }
            addSystem("提示: 支持 . 前缀，例如 .logout 等价 /logout。");
            return;
        }
        if ("/clear".equals(line)) {
            String peer = currentPeer.get();
            if (peer == null || peer.isBlank()) {
                chatLines.clear();
            } else {
                conversationMessages.put(peer, new ArrayList<>());
                String defaultPreview = isLocalContext(peer)
                    ? "未绑定发送对象，使用 /to 绑定"
                    : "暂无消息";
                conversationPreview.put(peer, defaultPreview);
                if (!isLocalContext(peer)) {
                    conversationTitle.remove(peer);
                }
                conversationUpdatedAt.put(peer, System.currentTimeMillis());
                conversationUnread.put(peer, 0);
                syncVisibleChatFromCurrentPeer();
                persistConversations();
            }
            addSystem("已清空当前会话对话区。");
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
                addSystem("暂无会话对象。可先 /new 创建窗口，再 /to <userId@im.wechat> 绑定。");
            } else {
                sortedPeers().forEach(peer -> {
                    String target = resolveTargetPeer(peer);
                    String targetSuffix = (target == null || target.isBlank() || peer.equals(target)) ? "" : " -> " + target;
                    if (peer.equals(currentPeer.get())) {
                        addSystem("* " + peer + targetSuffix + " (current)");
                    } else {
                        addSystem("* " + peer + targetSuffix);
                    }
                });
            }
            return;
        }

        if ("/to".equals(line)) {
            addWarn("用法: /to <userId@im.wechat>");
            return;
        }
        if ("/new".equals(line)) {
            String newContextId = createLocalContext("新窗口");
            switchToPeer(newContextId, false);
            addSystem("已创建本地窗口: " + newContextId + "，请用 /to 绑定发送对象。");
            return;
        }
        if (line.startsWith("/new ")) {
            String title = line.substring(5).trim();
            if (title.isBlank()) {
                addWarn("用法: /new [窗口名]");
                return;
            }
            String newContextId = createLocalContext(title);
            switchToPeer(newContextId, false);
            addSystem("已创建本地窗口: " + title + " (" + newContextId + ")，请用 /to 绑定发送对象。");
            return;
        }
        if ("/ctx".equals(line)) {
            List<String> candidates = sortedPeers();
            if (candidates.isEmpty()) {
                addWarn("暂无上下文对象，可先 /new 创建窗口。");
                return;
            }
            selectedPeerIndex = clampPeerIndex(selectedPeerIndex, candidates);
            addSystem("上下文面板已选中: " + (selectedPeerIndex + 1) + ". " + candidates.get(selectedPeerIndex));
            return;
        }
        if (line.startsWith("/ctx ")) {
            String value = line.substring(5).trim();
            if (value.isBlank()) {
                addWarn("用法: /ctx [序号|contextId]");
                return;
            }
            List<String> candidates = sortedPeers();
            if (candidates.isEmpty()) {
                addWarn("暂无上下文对象，可先 /new 创建窗口。");
                return;
            }
            try {
                int idx = Integer.parseInt(value);
                if (idx >= 1 && idx <= candidates.size()) {
                    selectPeerByIndex(idx - 1, candidates);
                    return;
                }
            } catch (NumberFormatException ignore) {
                // fallback by peer id
            }
            int matched = candidates.indexOf(value);
            if (matched >= 0) {
                selectPeerByIndex(matched, candidates);
                return;
            }
            addWarn("未找到上下文对象: " + value);
            return;
        }
        if (line.startsWith("/to ")) {
            String nextPeer = line.substring(4).trim();
            if (nextPeer.isBlank()) {
                addWarn("用法: /to <userId@im.wechat>");
                return;
            }
            String current = currentPeer.get();
            if (isLocalContext(current)) {
                contextTargetPeer.put(current, nextPeer);
                peers.add(nextPeer);
                primeConversationMetadata(Set.of(nextPeer, current));
                conversationUpdatedAt.put(current, System.currentTimeMillis());
                conversationPreview.putIfAbsent(current, "暂无消息");
                addSystem("当前窗口已绑定会话对象: " + nextPeer);
                persistConversations();
            } else {
                switchToPeer(nextPeer, true);
            }
            return;
        }

        if (line.startsWith("/media ")) {
            String[] parts = line.split("\\s+", 3);
            if (parts.length < 2) {
                addWarn("用法: /media <path|url> [caption]");
                return;
            }
            String contextId = currentPeer.get();
            String targetPeer = resolveTargetPeer(contextId);
            if (contextId == null || contextId.isBlank() || targetPeer == null || targetPeer.isBlank()) {
                addWarn("当前窗口未绑定发送对象，请先 /to <userId@im.wechat>。");
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
            addOut(contextId, "[媒体] " + media + (caption.isBlank() ? "" : "\n说明: " + caption));
            ioExecutor.submit(() -> {
                try {
                    String mid = currentSdk.sendMedia(currentAccountId, targetPeer, media, caption);
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
        conversationMessages.clear();
        conversationTitle.clear();
        contextTargetPeer.clear();
        conversationPreview.clear();
        conversationUnread.clear();
        conversationUpdatedAt.clear();
        chatLines.clear();
    }

    private void onInboundEvent(InboundMessageEvent event) {
        String body = safe(event.message().textBody(), "[non-text message]");

        String from = safe(event.message().fromUserId(), "(unknown)");
        peers.add(from);
        if (currentPeer.get() == null || currentPeer.get().isBlank()) {
            currentPeer.set(from);
        }
        List<String> targets = new ArrayList<>();
        targets.add(from);
        contextTargetPeer.forEach((contextId, targetPeer) -> {
            if (targetPeer != null && targetPeer.equals(from) && !targets.contains(contextId)) {
                targets.add(contextId);
            }
        });

        String current = currentPeer.get();
        for (String contextId : targets) {
            addIn(contextId, body);
            if (!contextId.equals(current)) {
                conversationUnread.merge(contextId, 1, Integer::sum);
            } else {
                conversationUnread.put(contextId, 0);
            }
        }
        alignPeerSelectionToCurrent();
        syncVisibleChatFromCurrentPeer();
        if (event.hasMedia()) {
            addSystem("媒体已保存: " + event.localMediaPath() + " (" + safe(event.mediaType(), "unknown") + ")");
        }
    }

    private void syncTypingState() {
        OpenClawWeixinSdk currentSdk = sdk;
        String currentAccountId = accountId;
        String peer = resolveTargetPeer(currentPeer.get());

        if (currentSdk == null || currentAccountId == null || currentAccountId.isBlank()) {
            statusText = "SDK 未就绪";
            return;
        }

        String input = currentInputText().trim();
        long now = System.currentTimeMillis();

        if (input.isBlank() || peer == null || peer.isBlank()) {
            statusText = "就绪";
            if (typingSent.get()) {
                if (peer == null || peer.isBlank()) {
                    typingSent.set(false);
                } else {
                    sendTypingAsync(peer, false);
                }
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
        String peer = resolveTargetPeer(currentPeer.get());
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
        if (!(text.startsWith("/") || text.startsWith("."))) {
            return List.of();
        }
        String normalized = normalizeSlashCommand(text);
        if ("/".equals(normalized)) {
            return CHAT_COMMANDS;
        }
        List<SlashCommand> out = new ArrayList<>();
        for (SlashCommand cmd : CHAT_COMMANDS) {
            if (cmd.syntax().startsWith(normalized)) {
                out.add(cmd);
            }
        }
        return out;
    }

    private static String normalizeSlashCommand(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String trimmed = input.trim();
        if (trimmed.startsWith(".")) {
            return "/" + trimmed.substring(1);
        }
        return trimmed;
    }

    private List<String> sortedPeers() {
        List<String> out = new ArrayList<>(peers);
        String current = currentPeer.get();
        if (current != null && !current.isBlank() && !out.contains(current)) {
            out.add(current);
        }
        out.sort((a, b) -> {
            long ta = conversationUpdatedAt.getOrDefault(a, 0L);
            long tb = conversationUpdatedAt.getOrDefault(b, 0L);
            if (ta != tb) {
                return Long.compare(tb, ta);
            }
            return a.compareTo(b);
        });
        return out;
    }

    private int clampPeerIndex(int index, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        if (index < 0) {
            return 0;
        }
        if (index >= candidates.size()) {
            return candidates.size() - 1;
        }
        return index;
    }

    private void alignPeerSelectionToCurrent() {
        List<String> candidates = sortedPeers();
        String current = currentPeer.get();
        if (candidates.isEmpty()) {
            selectedPeerIndex = 0;
            return;
        }
        if (current == null || current.isBlank()) {
            selectedPeerIndex = clampPeerIndex(selectedPeerIndex, candidates);
            return;
        }
        int idx = candidates.indexOf(current);
        selectedPeerIndex = idx >= 0 ? idx : clampPeerIndex(selectedPeerIndex, candidates);
    }

    private void selectPeerByIndex(int index, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            addWarn("暂无上下文对象，可先 /new 创建窗口。");
            return;
        }
        int clamped = clampPeerIndex(index, candidates);
        String nextPeer = candidates.get(clamped);
        switchToPeer(nextPeer, true);
        selectedPeerIndex = clamped;
    }

    private List<UiLine> buildContextLines(List<String> candidates) {
        List<UiLine> out = new ArrayList<>();
        out.add(UiLine.bold("会话上下文", CYAN));
        out.add(UiLine.of("", DIM));

        if (candidates == null || candidates.isEmpty()) {
            out.add(UiLine.of("暂无上下文对象", DIM));
            out.add(UiLine.of("可用 /new 创建窗口", DIM));
            return out;
        }

        String current = currentPeer.get();
        int selected = clampPeerIndex(selectedPeerIndex, candidates);
        for (int i = 0; i < candidates.size(); i++) {
            String peer = candidates.get(i);
            boolean cursor = i == selected;
            boolean active = peer.equals(current);
            String marker = cursor ? ">" : " ";
            String title = conversationTitle.getOrDefault(peer, displayPeerTitle(peer));
            String preview = conversationPreview.getOrDefault(peer, "暂无消息");
            String targetPeer = resolveTargetPeer(peer);
            String titleWithTarget = title;
            if (isLocalContext(peer)) {
                titleWithTarget = targetPeer == null || targetPeer.isBlank()
                    ? title + " [unbound]"
                    : title + " -> " + displayPeerTitle(targetPeer);
            }
            int unread = conversationUnread.getOrDefault(peer, 0);
            String suffix = active ? " [current]" : (unread > 0 ? " [" + unread + "]" : "");
            String line = marker + " " + truncate(titleWithTarget, 22) + suffix;
            if (cursor || active) {
                out.add(UiLine.bold(line, BRIGHT));
            } else {
                out.add(UiLine.of(line, DIM));
            }
            out.add(UiLine.of("  " + truncate(preview, 26), DIM));
            out.add(UiLine.of("", DIM));
        }
        return out;
    }

    private void switchToPeer(String nextPeer, boolean announce) {
        if (nextPeer == null || nextPeer.isBlank()) {
            return;
        }
        sendTypingCancelIfNeeded();
        peers.add(nextPeer);
        primeConversationMetadata(Set.of(nextPeer));
        if (!isLocalContext(nextPeer)) {
            contextTargetPeer.remove(nextPeer);
        }
        currentPeer.set(nextPeer);
        conversationUnread.put(nextPeer, 0);
        alignPeerSelectionToCurrent();
        syncVisibleChatFromCurrentPeer();
        persistConversations();
        if (announce) {
            addSystem("当前会话对象: " + nextPeer);
        }
    }

    private String createLocalContext(String rawTitle) {
        String title = (rawTitle == null || rawTitle.isBlank()) ? "新窗口" : rawTitle.strip();
        String contextId = LOCAL_CONTEXT_PREFIX + localContextSeq.incrementAndGet();
        peers.add(contextId);
        conversationTitle.put(contextId, title);
        conversationPreview.put(contextId, "未绑定发送对象，使用 /to 绑定");
        conversationUnread.put(contextId, 0);
        conversationUpdatedAt.put(contextId, System.currentTimeMillis());
        conversationMessages.put(contextId, new ArrayList<>());
        return contextId;
    }

    private boolean isLocalContext(String contextId) {
        return contextId != null && contextId.startsWith(LOCAL_CONTEXT_PREFIX);
    }

    private String resolveTargetPeer(String contextId) {
        if (contextId == null || contextId.isBlank()) {
            return null;
        }
        String bound = contextTargetPeer.get(contextId);
        if (bound != null && !bound.isBlank()) {
            return bound;
        }
        if (isLocalContext(contextId)) {
            return null;
        }
        return contextId;
    }

    private void primeConversationMetadata(Set<String> peerSet) {
        for (String peer : peerSet) {
            if (peer == null || peer.isBlank()) {
                continue;
            }
            conversationPreview.putIfAbsent(peer, "暂无消息");
            conversationUnread.putIfAbsent(peer, 0);
            conversationUpdatedAt.putIfAbsent(peer, 0L);
            conversationMessages.computeIfAbsent(peer, ignored -> new ArrayList<>());
        }
    }

    private void syncVisibleChatFromCurrentPeer() {
        chatLines.clear();
        String peer = currentPeer.get();
        if (peer == null || peer.isBlank()) {
            return;
        }
        List<ConversationMessage> history = conversationMessages.get(peer);
        if (history == null || history.isEmpty()) {
            return;
        }
        for (ConversationMessage message : history) {
            appendConversationBubbles(chatLines, message.outbound(), message.content());
        }
        trimChatLines();
    }

    private void loadPersistedConversations(String accountIdValue) {
        Map<String, FileConversationStore.ConversationSnapshot> persisted = conversationStore.load(accountIdValue);
        if (persisted.isEmpty()) {
            return;
        }
        for (Map.Entry<String, FileConversationStore.ConversationSnapshot> entry : persisted.entrySet()) {
            String peer = entry.getKey();
            if (peer == null || peer.isBlank()) {
                continue;
            }
            if (isLocalContext(peer)) {
                String suffix = peer.substring(LOCAL_CONTEXT_PREFIX.length());
                try {
                    long n = Long.parseLong(suffix);
                    localContextSeq.updateAndGet(prev -> Math.max(prev, n));
                } catch (NumberFormatException ignore) {
                    // ignore invalid context id format
                }
            }
            FileConversationStore.ConversationSnapshot snapshot = entry.getValue();
            peers.add(peer);

            if (snapshot.title() != null && !snapshot.title().isBlank()) {
                conversationTitle.put(peer, snapshot.title());
            }
            if (snapshot.targetPeer() != null && !snapshot.targetPeer().isBlank()) {
                contextTargetPeer.put(peer, snapshot.targetPeer());
            }
            if (snapshot.preview() != null && !snapshot.preview().isBlank()) {
                conversationPreview.put(peer, snapshot.preview());
            }
            conversationUpdatedAt.put(peer, snapshot.updatedAt());
            conversationUnread.put(peer, 0);

            List<ConversationMessage> messages = new ArrayList<>();
            for (FileConversationStore.ConversationMessage message : snapshot.messages()) {
                String content = message.content() == null ? "" : message.content().strip();
                if (content.isBlank()) {
                    continue;
                }
                messages.add(new ConversationMessage(message.outbound(), content));
            }
            trimConversationHistory(messages);
            conversationMessages.put(peer, messages);
        }
    }

    private void persistConversations() {
        String currentAccountId = accountId;
        if (currentAccountId == null || currentAccountId.isBlank()) {
            return;
        }
        Map<String, FileConversationStore.ConversationSnapshot> snapshots = new LinkedHashMap<>();
        for (String peer : sortedPeers()) {
            if (peer == null || peer.isBlank()) {
                continue;
            }
            List<ConversationMessage> source = conversationMessages.getOrDefault(peer, List.of());
            List<FileConversationStore.ConversationMessage> messages = new ArrayList<>(source.size());
            for (ConversationMessage message : source) {
                String content = message.content() == null ? "" : message.content().strip();
                if (content.isBlank()) {
                    continue;
                }
                messages.add(new FileConversationStore.ConversationMessage(message.outbound(), content));
            }

            String title = conversationTitle.get(peer);
            String targetPeer = contextTargetPeer.get(peer);
            String preview = conversationPreview.getOrDefault(peer, "暂无消息");
            long updatedAt = conversationUpdatedAt.getOrDefault(peer, 0L);
            snapshots.put(peer, new FileConversationStore.ConversationSnapshot(title, targetPeer, preview, updatedAt, List.copyOf(messages)));
        }
        conversationStore.save(currentAccountId, snapshots);
    }

    private String displayPeerTitle(String peer) {
        if (peer == null || peer.isBlank()) {
            return "(unknown)";
        }
        int at = peer.indexOf('@');
        if (at > 0) {
            return peer.substring(0, at);
        }
        return peer;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        if (max == 1) {
            return "…";
        }
        return text.substring(0, max - 1) + "…";
    }

    private static String firstLine(String content) {
        if (content == null || content.isBlank()) {
            return "(空消息)";
        }
        String compact = content.replace("\r\n", "\n");
        int pos = compact.indexOf('\n');
        String line = pos >= 0 ? compact.substring(0, pos) : compact;
        return line.strip().isEmpty() ? "(空消息)" : line.strip();
    }

    private void updateConversationMeta(String peer, String content, boolean outbound) {
        String line = firstLine(content);
        conversationTitle.computeIfAbsent(peer, ignored -> line);
        conversationPreview.put(peer, (outbound ? "你: " : "对方: ") + line);
        conversationUpdatedAt.put(peer, System.currentTimeMillis());
    }

    private void appendConversationBubbles(List<ChatBubble> target, boolean outbound, String content) {
        List<String> wrapped = wrapChatLines(content, CHAT_WRAP_WIDTH);
        if (outbound) {
            int width = wrapped.stream().mapToInt(String::length).max().orElse(1) + 2;
            target.add(ChatBubble.out("  " + " ".repeat(width)));
            for (String line : wrapped) {
                String payload = "> " + line;
                target.add(ChatBubble.out("  " + padRight(payload, width)));
            }
            target.add(ChatBubble.out("  " + " ".repeat(width)));
        } else {
            target.add(ChatBubble.in("  "));
            for (String line : wrapped) {
                target.add(ChatBubble.in("  " + line));
            }
            target.add(ChatBubble.in("  "));
            target.add(ChatBubble.inSeparator("─".repeat(INBOUND_SEPARATOR_WIDTH)));
        }
    }

    private static void trimConversationHistory(List<ConversationMessage> history) {
        if (history.size() <= CONVERSATION_MAX_MESSAGES) {
            return;
        }
        history.subList(0, history.size() - CONVERSATION_MAX_MESSAGES).clear();
    }

    private void appendChatMessage(String peer, boolean outbound, String content) {
        if (peer == null || peer.isBlank()) {
            return;
        }
        String normalized = content == null ? "" : content.strip();
        if (normalized.isBlank()) {
            normalized = "(空消息)";
        }

        peers.add(peer);
        primeConversationMetadata(Set.of(peer));
        List<ConversationMessage> history = conversationMessages.computeIfAbsent(peer, ignored -> new ArrayList<>());
        history.add(new ConversationMessage(outbound, normalized));
        trimConversationHistory(history);
        updateConversationMeta(peer, normalized, outbound);

        if (peer.equals(currentPeer.get())) {
            syncVisibleChatFromCurrentPeer();
        }
        persistConversations();
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
            return List.of(
                "/help  显示完整命令",
                "/new [窗口名]  新建本地窗口",
                "/to <userId>  绑定当前窗口发送对象",
                "/users  查看窗口/会话",
                "/logout  注销账号"
            );
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
            case CHAT -> "输入消息；/new 新建窗口；/to 绑定发送对象；↑/↓ 选择左侧上下文；输入 / 或 . 查看命令";
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

    private void addOut(String peer, String content) {
        appendChatMessage(peer, true, content);
    }

    private void addIn(String peer, String content) {
        appendChatMessage(peer, false, content);
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
     * @since 2026-04-21
     * @author LangChat Team
     */
    private record ConversationMessage(
        boolean outbound,
        String content
    ) {
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
