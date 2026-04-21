package cn.langchat.openclaw.weixin.cli;

import cn.langchat.openclaw.weixin.storage.AccountIdCompat;
import cn.langchat.openclaw.weixin.storage.StateDirectoryResolver;
import cn.langchat.openclaw.weixin.util.Jsons;
import cn.langchat.openclaw.weixin.util.MapValues;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
public final class FileConversationStore {
    private final Path baseDir;

    public FileConversationStore() {
        this(StateDirectoryResolver.resolveStateDir().resolve("openclaw-weixin").resolve("cli-conversations"));
    }

    public FileConversationStore(Path baseDir) {
        this.baseDir = baseDir;
    }

    public Map<String, ConversationSnapshot> load(String accountId) {
        Path file = resolvePath(accountId);
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> root = Jsons.parseObject(raw);
            Map<String, Object> conversations = MapValues.map(root, "conversations");
            if (conversations == null || conversations.isEmpty()) {
                return Map.of();
            }

            Map<String, ConversationSnapshot> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : conversations.entrySet()) {
                String peer = entry.getKey();
                if (!(entry.getValue() instanceof Map<?, ?> anyMap)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> conv = (Map<String, Object>) anyMap;

                String title = MapValues.optionalString(conv, "title").orElse(null);
                String targetPeer = MapValues.optionalString(conv, "target_peer").orElse(null);
                String preview = MapValues.optionalString(conv, "preview").orElse("暂无消息");
                long updatedAt = MapValues.optionalLong(conv, "updated_at").orElse(0L);

                List<ConversationMessage> messages = new ArrayList<>();
                for (Object item : MapValues.list(conv, "messages")) {
                    if (!(item instanceof Map<?, ?> messageAny)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) messageAny;
                    String content = MapValues.optionalString(message, "content").orElse("").strip();
                    if (content.isBlank()) {
                        continue;
                    }
                    boolean outbound = Optional.ofNullable(message.get("outbound"))
                        .map(String::valueOf)
                        .map(Boolean::parseBoolean)
                        .orElse(false);
                    messages.add(new ConversationMessage(outbound, content));
                }
                out.put(peer, new ConversationSnapshot(title, targetPeer, preview, updatedAt, List.copyOf(messages)));
            }
            return Map.copyOf(out);
        } catch (Exception ignore) {
            return Map.of();
        }
    }

    public void save(String accountId, Map<String, ConversationSnapshot> conversations) {
        Path file = resolvePath(accountId);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);

        Map<String, Object> body = new LinkedHashMap<>();
        for (Map.Entry<String, ConversationSnapshot> entry : conversations.entrySet()) {
            String peer = entry.getKey();
            ConversationSnapshot snapshot = entry.getValue();

            Map<String, Object> conv = new LinkedHashMap<>();
            if (snapshot.title() != null && !snapshot.title().isBlank()) {
                conv.put("title", snapshot.title());
            }
            if (snapshot.targetPeer() != null && !snapshot.targetPeer().isBlank()) {
                conv.put("target_peer", snapshot.targetPeer());
            }
            conv.put("preview", snapshot.preview() == null ? "暂无消息" : snapshot.preview());
            conv.put("updated_at", snapshot.updatedAt());

            List<Map<String, Object>> messages = new ArrayList<>();
            for (ConversationMessage message : snapshot.messages()) {
                if (message.content() == null || message.content().isBlank()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("outbound", message.outbound());
                row.put("content", message.content());
                messages.add(row);
            }
            conv.put("messages", messages);
            body.put(peer, conv);
        }
        root.put("conversations", body);

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Jsons.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Persist conversation failed: " + file, ex);
        }
    }

    private Path resolvePath(String accountId) {
        String normalized = AccountIdCompat.normalizeLikeTs(accountId);
        return baseDir.resolve(normalized + ".conversations.json");
    }

    /**
     * @since 2026-04-21
     * @author LangChat Team
     */
    public record ConversationSnapshot(
        String title,
        String targetPeer,
        String preview,
        long updatedAt,
        List<ConversationMessage> messages
    ) {
    }

    /**
     * @since 2026-04-21
     * @author LangChat Team
     */
    public record ConversationMessage(
        boolean outbound,
        String content
    ) {
    }
}
