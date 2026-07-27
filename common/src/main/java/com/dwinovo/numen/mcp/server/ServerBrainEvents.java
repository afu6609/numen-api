package com.dwinovo.numen.mcp.server;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small, bounded hand-off queue from the dedicated Minecraft server to an
 * external agent brain.
 *
 * <p>Forge game events publish on the server thread while MCP requests drain
 * from HTTP worker threads, so all queue mutations are synchronized. The queue
 * deliberately drops the oldest event when full: stale chat must never grow
 * without bound or delay current players after the sidecar reconnects.
 */
@com.dwinovo.numen.api.Internal
public final class ServerBrainEvents {

    static final int MAX_QUEUED_EVENTS = 256;
    private static final int MAX_CHAT_LENGTH = 256;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();

    private ServerBrainEvents() {}

    public record Event(
            long id,
            String type,
            UUID playerUuid,
            String playerName,
            String message,
            long gameTime,
            long receivedAtEpochMillis) {}

    public static void publishChat(
            UUID playerUuid,
            String playerName,
            String message,
            long gameTime) {
        if (playerUuid == null || playerName == null || message == null) return;
        String cleanName = playerName.trim();
        String cleanMessage = message.trim();
        if (cleanName.isEmpty() || cleanMessage.isEmpty()) return;
        if (cleanMessage.length() > MAX_CHAT_LENGTH) {
            cleanMessage = cleanMessage.substring(0, MAX_CHAT_LENGTH);
        }
        Event event = new Event(
                SEQUENCE.incrementAndGet(),
                "player_chat",
                playerUuid,
                cleanName,
                cleanMessage,
                gameTime,
                Instant.now().toEpochMilli());
        synchronized (EVENTS) {
            while (EVENTS.size() >= MAX_QUEUED_EVENTS) EVENTS.removeFirst();
            EVENTS.addLast(event);
        }
    }

    public static List<Event> poll(int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 64));
        synchronized (EVENTS) {
            List<Event> drained = new ArrayList<>(Math.min(limit, EVENTS.size()));
            while (drained.size() < limit && !EVENTS.isEmpty()) {
                drained.add(EVENTS.removeFirst());
            }
            return List.copyOf(drained);
        }
    }

    static void clearForTests() {
        synchronized (EVENTS) {
            EVENTS.clear();
        }
    }
}
