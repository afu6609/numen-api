package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.api.ServerBrainAdminEvents;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
            UUID companionUuid,
            String companionName,
            String taskId,
            String taskName,
            String status,
            String message,
            String runId,
            ServerBrainAdminEvents.ArenaAnchor arenaAnchor,
            Boolean freshThread,
            long gameTime,
            long receivedAtEpochMillis,
            Map<String, Object> data) {}

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
                null,
                null,
                null,
                null,
                null,
                cleanMessage,
                null,
                null,
                null,
                gameTime,
                Instant.now().toEpochMilli(),
                Map.of());
        enqueue(event);
    }

    /**
     * Wake the external dedicated-server brain when one of its background
     * actions reaches a terminal state. The event intentionally contains only
     * the task envelope; the brain re-perceives the live world before deciding
     * whether the player's original goal is complete.
     */
    public static void publishTaskFinished(
            UUID companionUuid,
            String companionName,
            String taskId,
            String taskName,
            String status,
            String message,
            long gameTime) {
        if (companionUuid == null || companionName == null
                || taskId == null || taskName == null || status == null) {
            return;
        }
        String cleanName = companionName.trim();
        String cleanTaskId = taskId.trim();
        String cleanTaskName = taskName.trim();
        String cleanStatus = status.trim();
        if (cleanName.isEmpty() || cleanTaskId.isEmpty()
                || cleanTaskName.isEmpty() || cleanStatus.isEmpty()) {
            return;
        }
        String cleanMessage = message == null ? "" : message.trim();
        if (cleanMessage.length() > MAX_CHAT_LENGTH) {
            cleanMessage = cleanMessage.substring(0, MAX_CHAT_LENGTH);
        }
        enqueue(new Event(
                SEQUENCE.incrementAndGet(),
                "task_finished",
                null,
                null,
                companionUuid,
                cleanName,
                cleanTaskId,
                cleanTaskName,
                cleanStatus,
                cleanMessage,
                null,
                null,
                null,
                gameTime,
                Instant.now().toEpochMilli(),
                Map.of()));
    }

    /**
     * Publication target for the public, trusted-server administrative API.
     * This method is engine plumbing; callers should use
     * {@link ServerBrainAdminEvents#publishTestInstruction}.
     */
    @com.dwinovo.numen.api.Internal
    public static void publishTestInstruction(
            UUID companionUuid,
            String companionName,
            String runId,
            String instruction,
            ServerBrainAdminEvents.ArenaAnchor arenaAnchor,
            boolean freshThread,
            long gameTime) {
        enqueue(new Event(
                SEQUENCE.incrementAndGet(),
                "test_instruction",
                null,
                null,
                companionUuid,
                companionName,
                null,
                null,
                null,
                instruction,
                runId,
                arenaAnchor,
                freshThread,
                gameTime,
                Instant.now().toEpochMilli(),
                Map.of()));
    }

    /**
     * Publish an operator-authored runtime-configuration request.
     *
     * <p>The request is server-wide and deliberately has no companion id: it
     * must remain available while the configured body is dormant or absent.
     */
    @com.dwinovo.numen.api.Internal
    public static void publishBrainConfigRequest(
            String requestId,
            Map<String, Object> data,
            long gameTime) {
        if (requestId == null || requestId.isBlank() || data == null) {
            throw new IllegalArgumentException(
                    "brain configuration request id and data are required");
        }
        if (!requestId.equals(data.get("requestId"))) {
            throw new IllegalArgumentException(
                    "brain configuration request id must match its data envelope");
        }
        enqueue(new Event(
                SEQUENCE.incrementAndGet(),
                "brain_config_request",
                null,
                null,
                null,
                null,
                null,
                null,
                "pending",
                "",
                null,
                null,
                null,
                gameTime,
                Instant.now().toEpochMilli(),
                Map.copyOf(data)));
    }

    private static void enqueue(Event event) {
        synchronized (EVENTS) {
            while (EVENTS.size() >= MAX_QUEUED_EVENTS) {
                if (dropOldestChat()) continue;
                if (isControlEvent(event)) {
                    if (dropOldestNonControlEvent()) continue;
                    throw new IllegalStateException(
                            "server-brain control event queue is full");
                }
                if (dropOldestNonControlEvent()) continue;
                // A burst of ordinary telemetry/chat must never evict an
                // already accepted operator/test control request.
                return;
            }
            EVENTS.addLast(event);
        }
    }

    /**
     * Preserve scarce operator/test control messages during ordinary chat
     * bursts. If the queue contains no chat, the normal oldest-event policy is
     * used by the caller.
     */
    private static boolean dropOldestChat() {
        Iterator<Event> iterator = EVENTS.iterator();
        while (iterator.hasNext()) {
            if ("player_chat".equals(iterator.next().type())) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static boolean dropOldestNonControlEvent() {
        Iterator<Event> iterator = EVENTS.iterator();
        while (iterator.hasNext()) {
            String type = iterator.next().type();
            if (!isControlEvent(type)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static boolean isControlEvent(Event event) {
        return isControlEvent(event.type());
    }

    private static boolean isControlEvent(String type) {
        return "brain_config_request".equals(type)
                || "test_instruction".equals(type);
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
