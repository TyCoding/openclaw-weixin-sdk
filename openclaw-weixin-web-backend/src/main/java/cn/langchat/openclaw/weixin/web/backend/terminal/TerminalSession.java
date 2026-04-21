package cn.langchat.openclaw.weixin.web.backend.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
public class TerminalSession implements AutoCloseable {
    private final String sessionId;
    private final PtyProcess process;
    private final String commandLine;
    private final String workspace;
    private final String createdAt;

    private final AtomicLong lastActiveAt = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final ExecutorService outputPump = Executors.newSingleThreadExecutor(new DaemonThreadFactory("terminal-output"));
    private final Map<String, Consumer<String>> outputListeners = new ConcurrentHashMap<>();

    public TerminalSession(PtyProcess process, String commandLine, String workspace) {
        this.sessionId = UUID.randomUUID().toString();
        this.process = process;
        this.commandLine = commandLine;
        this.workspace = workspace;
        this.createdAt = Instant.now().toString();
        startOutputPump();
    }

    public String sessionId() {
        return sessionId;
    }

    public String commandLine() {
        return commandLine;
    }

    public String workspace() {
        return workspace;
    }

    public String createdAt() {
        return createdAt;
    }

    public long lastActiveAt() {
        return lastActiveAt.get();
    }

    public boolean isAlive() {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException ex) {
            return true;
        }
    }

    public void touch() {
        lastActiveAt.set(System.currentTimeMillis());
    }

    public String addOutputListener(Consumer<String> listener) {
        String id = UUID.randomUUID().toString();
        outputListeners.put(id, listener);
        return id;
    }

    public void removeOutputListener(String listenerId) {
        if (listenerId == null || listenerId.isBlank()) {
            return;
        }
        outputListeners.remove(listenerId);
    }

    public void write(String data) {
        if (data == null || data.isEmpty() || closed.get()) {
            return;
        }
        touch();
        try {
            OutputStream out = process.getOutputStream();
            synchronized (out) {
                out.write(data.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException ignore) {
            // transport may already be closed
        }
    }

    public void resize(int cols, int rows) {
        if (closed.get()) {
            return;
        }
        int c = Math.max(20, cols);
        int r = Math.max(8, rows);
        try {
            process.setWinSize(new WinSize(c, r));
        } catch (Exception ignore) {
            // unsupported on some environments
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        outputPump.shutdownNow();
        process.destroy();
    }

    private void startOutputPump() {
        outputPump.submit(() -> {
            try (InputStream in = process.getInputStream(); Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                char[] buf = new char[4096];
                while (!closed.get()) {
                    int n = reader.read(buf);
                    if (n < 0) {
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }
                    touch();
                    emit(new String(buf, 0, n));
                }
            } catch (IOException ignore) {
                // process stream closed
            } finally {
                emit("\r\n[terminal session closed]\r\n");
            }
        });
    }

    private void emit(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        outputListeners.values().forEach(listener -> {
            try {
                listener.accept(text);
            } catch (Exception ignore) {
                // ignore bad listener
            }
        });
    }

    /**
     * @since 2026-04-21
     * @author LangChat Team
     */
    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String name;

        private DaemonThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, name + "-" + UUID.randomUUID());
            t.setDaemon(true);
            return t;
        }
    }
}
