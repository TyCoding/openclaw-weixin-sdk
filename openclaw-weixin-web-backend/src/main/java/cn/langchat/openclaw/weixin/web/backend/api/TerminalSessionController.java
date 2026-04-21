package cn.langchat.openclaw.weixin.web.backend.api;

import cn.langchat.openclaw.weixin.web.backend.terminal.TerminalSession;
import cn.langchat.openclaw.weixin.web.backend.terminal.TerminalSessionManager;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    @GetMapping(value = "/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrcode(
        @RequestParam("text") @NotBlank String text,
        @RequestParam(name = "size", required = false, defaultValue = "220") @Min(120) @Max(640) int size
    ) {
        String payload = text.trim();
        if (payload.length() > 4096) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);

            var matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints);
            ByteArrayOutputStream output = new ByteArrayOutputStream(size * size / 2);
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);

            HttpHeaders headers = new HttpHeaders();
            headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.MINUTES).cachePrivate().mustRevalidate());
            headers.setPragma("no-cache");
            return ResponseEntity.ok().headers(headers).contentType(MediaType.IMAGE_PNG).body(output.toByteArray());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().build();
        }
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
