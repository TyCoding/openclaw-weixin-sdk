package cn.langchat.openclaw.weixin.web.backend.terminal;

import cn.langchat.openclaw.weixin.web.backend.config.TerminalProperties;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
@Component
public class TerminalSessionManager {
    private final TerminalProperties properties;
    private final ConcurrentMap<String, TerminalSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "terminal-session-sweeper");
        t.setDaemon(true);
        return t;
    });

    public TerminalSessionManager(TerminalProperties properties) {
        this.properties = properties;
    }

    private static Path resolveWorkspace(Path configured) {
        Path resolved = configured == null ? Path.of(System.getProperty("user.dir")) : configured;
        if (!Files.exists(resolved)) {
            throw new IllegalStateException("workspaceDir not found: " + resolved);
        }
        if (!Files.isDirectory(resolved)) {
            throw new IllegalStateException("workspaceDir is not directory: " + resolved);
        }
        return resolved;
    }

    @PostConstruct
    public void startSweeper() {
        sweeper.scheduleAtFixedRate(this::cleanupSessions, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        sweeper.shutdownNow();
        sessions.values().forEach(TerminalSession::close);
        sessions.clear();
    }

    public TerminalSession createSession() throws IOException {
        return createSession(SessionLaunchOptions.defaultOptions());
    }

    public TerminalSession createSession(SessionLaunchOptions options) throws IOException {
        Path workspace = resolveWorkspace(properties.getWorkspaceDir());
        List<String> command = resolveCommand(options);

        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.putIfAbsent("TERM", "xterm-256color");
        env.putIfAbsent("LANG", "en_US.UTF-8");
        env.putIfAbsent("LC_ALL", "en_US.UTF-8");

        PtyProcess process = new PtyProcessBuilder(command.toArray(String[]::new))
            .setDirectory(workspace.toAbsolutePath().toString())
            .setEnvironment(env)
            .setInitialColumns(properties.getDefaultCols())
            .setInitialRows(properties.getDefaultRows())
            .start();

        TerminalSession session = new TerminalSession(
            process,
            String.join(" ", command),
            workspace.toAbsolutePath().toString()
        );
        sessions.put(session.sessionId(), session);
        return session;
    }

    private List<String> resolveCommand(SessionLaunchOptions options) {
        List<String> base = properties.getCommand();
        if (base == null || base.isEmpty()) {
            throw new IllegalStateException("terminal command is empty");
        }

        String mode = "login".equalsIgnoreCase(options.mode()) ? "login" : "chat";
        String accountId = options.accountId();
        boolean forceNew = options.forceNew();

        List<String> resolved = new ArrayList<>(base);
        int size = resolved.size();
        if (size >= 2 && "-lc".equals(resolved.get(size - 2))) {
            String script = resolved.get(size - 1);
            script = script.replace("openclaw-weixin login", "openclaw-weixin " + mode);
            script = script.replace("openclaw-weixin chat", "openclaw-weixin " + mode);
            if (accountId != null && !accountId.isBlank()) {
                script = script + " --account-id " + shellQuote(accountId);
            }
            if (forceNew && "chat".equals(mode)) {
                script = script + " --new";
            }
            resolved.set(size - 1, script);
            return resolved;
        }

        int commandModeIndex = findCommandModeIndex(resolved);
        if (commandModeIndex >= 0) {
            resolved.set(commandModeIndex, mode);
        } else {
            resolved.add(mode);
        }
        if (accountId != null && !accountId.isBlank()) {
            resolved.add("--account-id");
            resolved.add(accountId);
        }
        if (forceNew && "chat".equals(mode)) {
            resolved.add("--new");
        }
        return resolved;
    }

    private static int findCommandModeIndex(List<String> command) {
        for (int i = 0; i < command.size(); i++) {
            String token = command.get(i);
            if ("chat".equals(token) || "login".equals(token)) {
                return i;
            }
        }
        return -1;
    }

    private static String shellQuote(String raw) {
        return "'" + raw.replace("'", "'\"'\"'") + "'";
    }

    public Optional<TerminalSession> findSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public boolean closeSession(String sessionId) {
        TerminalSession session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        session.close();
        return true;
    }

    public List<SessionView> listSessions() {
        return sessions.values().stream()
            .map(SessionView::from)
            .sorted(Comparator.comparing(SessionView::createdAt))
            .toList();
    }

    private void cleanupSessions() {
        long now = System.currentTimeMillis();
        long timeout = Math.max(60_000L, properties.getSessionIdleTimeoutMs());

        List<String> toClose = new ArrayList<>();
        sessions.forEach((id, session) -> {
            boolean idleExpired = (now - session.lastActiveAt()) > timeout;
            if (!session.isAlive() || idleExpired) {
                toClose.add(id);
            }
        });

        toClose.forEach(this::closeSession);
    }

    /**
     * @since 2026-04-21
     * @author LangChat Team
     */
    public record SessionView(
        String sessionId,
        String commandLine,
        String workspace,
        String createdAt,
        long lastActiveAt,
        boolean alive
    ) {
        static SessionView from(TerminalSession session) {
            return new SessionView(
                session.sessionId(),
                session.commandLine(),
                session.workspace(),
                session.createdAt(),
                session.lastActiveAt(),
                session.isAlive()
            );
        }
    }

    /**
     * @since 2026-04-21
     * @author LangChat Team
     */
    public record SessionLaunchOptions(
        String mode,
        String accountId,
        boolean forceNew
    ) {
        public static SessionLaunchOptions defaultOptions() {
            return new SessionLaunchOptions("chat", null, false);
        }
    }
}
