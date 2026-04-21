package cn.langchat.openclaw.weixin.web.backend.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {
    private static final String ATTR_SESSION_ID = "terminalSessionId";
    private static final String ATTR_LISTENER_ID = "terminalListenerId";

    private final TerminalSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    public TerminalWebSocketHandler(TerminalSessionManager sessionManager, ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocketSession) throws Exception {
        String terminalSessionId = extractTerminalSessionId(webSocketSession.getUri())
            .orElseThrow(() -> new IllegalArgumentException("missing terminal session id in path"));

        TerminalSession session = sessionManager.findSession(terminalSessionId)
            .orElseThrow(() -> new IllegalStateException("terminal session not found: " + terminalSessionId));

        String listenerId = session.addOutputListener(output -> sendTextSafely(webSocketSession, output));
        session.touch();

        webSocketSession.getAttributes().put(ATTR_SESSION_ID, terminalSessionId);
        webSocketSession.getAttributes().put(ATTR_LISTENER_ID, listenerId);
        sendTextSafely(webSocketSession, "\u001b[90m[connected] session=" + terminalSessionId + "\u001b[0m\r\n");
    }

    @Override
    protected void handleTextMessage(WebSocketSession webSocketSession, TextMessage message) {
        String terminalSessionId = (String) webSocketSession.getAttributes().get(ATTR_SESSION_ID);
        if (terminalSessionId == null || terminalSessionId.isBlank()) {
            return;
        }

        Optional<TerminalSession> maybeSession = sessionManager.findSession(terminalSessionId);
        if (maybeSession.isEmpty()) {
            sendTextSafely(webSocketSession, "\r\n[session expired]\r\n");
            return;
        }

        TerminalSession session = maybeSession.get();
        session.touch();

        String payload = message.getPayload();
        if (payload == null || payload.isEmpty()) {
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(payload);
            String type = node.path("type").asText("");
            switch (type) {
                case "input" -> session.write(node.path("data").asText(""));
                case "resize" -> session.resize(node.path("cols").asInt(120), node.path("rows").asInt(32));
                default -> session.write(payload);
            }
        } catch (Exception ignore) {
            session.write(payload);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession webSocketSession, CloseStatus status) {
        String terminalSessionId = (String) webSocketSession.getAttributes().get(ATTR_SESSION_ID);
        String listenerId = (String) webSocketSession.getAttributes().get(ATTR_LISTENER_ID);
        if (terminalSessionId == null || listenerId == null) {
            return;
        }
        sessionManager.findSession(terminalSessionId).ifPresent(session -> session.removeOutputListener(listenerId));
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private static Optional<String> extractTerminalSessionId(URI uri) {
        if (uri == null || uri.getPath() == null || uri.getPath().isBlank()) {
            return Optional.empty();
        }
        String[] parts = uri.getPath().split("/");
        if (parts.length == 0) {
            return Optional.empty();
        }
        String id = parts[parts.length - 1];
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(id);
    }

    private static void sendTextSafely(WebSocketSession webSocketSession, String text) {
        if (!webSocketSession.isOpen()) {
            return;
        }
        synchronized (webSocketSession) {
            try {
                webSocketSession.sendMessage(new TextMessage(text));
            } catch (IOException ignore) {
                // best effort send
            }
        }
    }
}
