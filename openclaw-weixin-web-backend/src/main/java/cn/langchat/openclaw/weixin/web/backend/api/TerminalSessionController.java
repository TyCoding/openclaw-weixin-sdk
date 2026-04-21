package cn.langchat.openclaw.weixin.web.backend.api;

import cn.langchat.openclaw.weixin.web.backend.terminal.TerminalSession;
import cn.langchat.openclaw.weixin.web.backend.terminal.TerminalSessionManager;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
@Validated
@RestController
@RequestMapping("/api/terminal/sessions")
public class TerminalSessionController {
    private final TerminalSessionManager sessionManager;

    public TerminalSessionController(TerminalSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @GetMapping
    public List<TerminalSessionManager.SessionView> listSessions() {
        return sessionManager.listSessions();
    }

    @PostMapping
    public CreateSessionResponse createSession(@RequestBody(required = false) CreateSessionRequest request) throws IOException {
        TerminalSessionManager.SessionLaunchOptions options = request == null
            ? TerminalSessionManager.SessionLaunchOptions.defaultOptions()
            : new TerminalSessionManager.SessionLaunchOptions(
                request.mode() == null || request.mode().isBlank() ? "chat" : request.mode(),
                request.accountId(),
                Boolean.TRUE.equals(request.forceNew())
            );
        TerminalSession session = sessionManager.createSession(options);
        return new CreateSessionResponse(
            session.sessionId(),
            "/ws/terminal/" + session.sessionId(),
            session.commandLine(),
            session.workspace(),
            session.createdAt()
        );
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> closeSession(@PathVariable String sessionId) {
        boolean closed = sessionManager.closeSession(sessionId);
        return closed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{sessionId}/input")
    public ResponseEntity<Void> writeInput(@PathVariable String sessionId, @RequestBody @Valid InputRequest request) {
        return sessionManager.findSession(sessionId)
            .map(session -> {
                session.write(request.data());
                return ResponseEntity.accepted().<Void>build();
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{sessionId}/resize")
    public ResponseEntity<Void> resize(@PathVariable String sessionId, @RequestBody @Valid ResizeRequest request) {
        return sessionManager.findSession(sessionId)
            .map(session -> {
                session.resize(request.cols(), request.rows());
                return ResponseEntity.accepted().<Void>build();
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * @since 2026-04-21
     * @author LangChat Team
     */
    public record CreateSessionResponse(
        String sessionId,
        String wsPath,
        String commandLine,
        String workspace,
        String createdAt
    ) {
    }

    /**
     * @since 2026-04-21
     * @author LangChat Team
     */
    public record CreateSessionRequest(
        String mode,
        String accountId,
        Boolean forceNew
    ) {
    }

    /**
     * @since 2026-04-21
     * @author LangChat Team
     */
    public record InputRequest(@NotBlank String data) {
    }

    /**
     * @since 2026-04-21
     * @author LangChat Team
     */
    public record ResizeRequest(
        @Min(20) @Max(500) int cols,
        @Min(8) @Max(200) int rows
    ) {
    }
}
