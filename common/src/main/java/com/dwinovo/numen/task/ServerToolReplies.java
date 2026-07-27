package com.dwinovo.numen.task;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Completion routes for tool calls issued directly inside the dedicated
 * server process.
 *
 * <p>The original Numen route sends completed task records to the owner's
 * client as a network payload. A headless brain has no owner client to receive
 * that packet, so {@code ServerNumenActuator} parks a callback here. When the
 * normal task scheduler drains a result it tries this route first and falls
 * back to the original network transport when no callback is registered.
 */
@com.dwinovo.numen.api.Internal
public final class ServerToolReplies {

    private record Route(UUID companion, Consumer<String> completion) {}

    private static final Map<String, Route> WAITING = new ConcurrentHashMap<>();

    private ServerToolReplies() {}

    /**
     * Register exactly one completion route for a tool-call id.
     *
     * @throws IllegalStateException when the id is already in flight
     */
    public static void register(
            String toolCallId,
            UUID companion,
            Consumer<String> completion) {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId is required");
        }
        if (companion == null) {
            throw new IllegalArgumentException("companion is required");
        }
        if (completion == null) {
            throw new IllegalArgumentException("completion is required");
        }
        Route prior = WAITING.putIfAbsent(toolCallId, new Route(companion, completion));
        if (prior != null) {
            throw new IllegalStateException("duplicate server tool-call id: " + toolCallId);
        }
    }

    /**
     * Complete and forget a parked server call.
     *
     * @return true when this was a server-local call, false when the caller
     *         should use the original client-network result route
     */
    public static boolean deliver(String toolCallId, String resultJson) {
        Route route = WAITING.remove(toolCallId);
        if (route == null) return false;
        route.completion().accept(resultJson);
        return true;
    }

    /**
     * Fail every parked synchronous call for a body that just died. The
     * owner-client route is resolved by the normal death payload; a headless
     * dedicated-server route needs an equivalent local completion.
     */
    public static int failForCompanion(UUID companion, String resultJson) {
        if (companion == null) return 0;
        int delivered = 0;
        for (Map.Entry<String, Route> entry : WAITING.entrySet()) {
            Route route = entry.getValue();
            if (!companion.equals(route.companion())) continue;
            if (WAITING.remove(entry.getKey(), route)) {
                route.completion().accept(resultJson);
                delivered++;
            }
        }
        return delivered;
    }

    /** Forget an abandoned/timed-out call. */
    public static void remove(String toolCallId) {
        if (toolCallId != null) WAITING.remove(toolCallId);
    }

    static int waitingCount() {
        return WAITING.size();
    }

    static void clearForTests() {
        WAITING.clear();
    }
}
