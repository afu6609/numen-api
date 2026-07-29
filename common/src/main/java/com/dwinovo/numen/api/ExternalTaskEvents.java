package com.dwinovo.numen.api;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Stable compatibility seam for server-side embodied runtimes that submit
 * work through a Numen body driver.
 *
 * <p>This is deliberately not an event queue and does not expose scheduler
 * internals. Listeners run on the Minecraft server thread when one external
 * asynchronous task reaches a terminal state.
 */
public final class ExternalTaskEvents {

    private static final CopyOnWriteArrayList<Consumer<Event>> LISTENERS =
            new CopyOnWriteArrayList<>();

    private ExternalTaskEvents() {}

    public record Event(
            UUID companionUuid,
            String companionName,
            String taskId,
            String taskName,
            String status,
            String message,
            long gameTime) {}

    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Subscribe to future external task completions. Closing the returned
     * handle is idempotent.
     */
    public static Subscription subscribe(Consumer<Event> listener) {
        if (listener == null) throw new IllegalArgumentException("listener is required");
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** Scheduler publication point; intended for the Numen engine only. */
    @Internal
    public static void publish(Event event) {
        if (event == null) return;
        for (Consumer<Event> listener : LISTENERS) {
            try {
                listener.accept(event);
            } catch (RuntimeException failure) {
                com.dwinovo.numen.Constants.LOG.error(
                        "external task listener failed for {}",
                        event.taskId(),
                        failure);
            }
        }
    }

    static void clearForTests() {
        LISTENERS.clear();
    }
}
