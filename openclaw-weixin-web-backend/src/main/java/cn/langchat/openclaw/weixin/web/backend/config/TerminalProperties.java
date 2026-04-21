package cn.langchat.openclaw.weixin.web.backend.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.List;

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
@Validated
@ConfigurationProperties(prefix = "openclaw.web.terminal")
public class TerminalProperties {
    private Path workspaceDir = Path.of(System.getProperty("user.dir"));

    @NotEmpty
    private List<String> command = List.of("/bin/zsh", "-lc", "./bin/openclaw-weixin chat");

    private int defaultCols = 140;
    private int defaultRows = 40;

    private long sessionIdleTimeoutMs = 8 * 60 * 60 * 1000L;

    public Path getWorkspaceDir() {
        return workspaceDir;
    }

    public void setWorkspaceDir(Path workspaceDir) {
        this.workspaceDir = workspaceDir;
    }

    public List<String> getCommand() {
        return command;
    }

    public void setCommand(List<String> command) {
        this.command = command;
    }

    public int getDefaultCols() {
        return defaultCols;
    }

    public void setDefaultCols(int defaultCols) {
        this.defaultCols = defaultCols;
    }

    public int getDefaultRows() {
        return defaultRows;
    }

    public void setDefaultRows(int defaultRows) {
        this.defaultRows = defaultRows;
    }

    public long getSessionIdleTimeoutMs() {
        return sessionIdleTimeoutMs;
    }

    public void setSessionIdleTimeoutMs(long sessionIdleTimeoutMs) {
        this.sessionIdleTimeoutMs = sessionIdleTimeoutMs;
    }
}
