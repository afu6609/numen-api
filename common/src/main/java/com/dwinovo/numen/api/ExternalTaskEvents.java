package com.dwinovo.numen.api;

import com.dwinovo.numen.mcp.server.ServerBrainEvents;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
    private static final CopyOnWriteArrayList<Predicate<Event>> CLAIMING_LISTENERS =
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

    /**
     * Subscribe a lifecycle adapter that may claim an underlying completion.
     *
     * <p>A claiming listener returns {@code true} only when it owns the task and
     * will publish a replacement terminal event after applying its own
     * postcondition checks. Ordinary observers registered through
     * {@link #subscribe(Consumer)} still receive the underlying event. This
     * preserves the original compatibility API while allowing a higher-level
     * embodied job to prevent a premature raw completion from reaching the
     * external brain.</p>
     */
    public static Subscription subscribeClaiming(Predicate<Event> listener) {
        if (listener == null) throw new IllegalArgumentException("listener is required");
        CLAIMING_LISTENERS.add(listener);
        return () -> CLAIMING_LISTENERS.remove(listener);
    }

    /** Scheduler publication point; intended for the Numen engine only. */
    @Internal
    public static void publish(Event event) {
        publishClaimable(event);
    }

    /**
     * Publish one completion and report whether a higher-level lifecycle
     * adapter claimed responsibility for the external-brain terminal event.
     */
    @Internal
    public static boolean publishClaimable(Event event) {
        if (event == null) return false;
        boolean claimed = false;
        for (Predicate<Event> listener : CLAIMING_LISTENERS) {
            try {
                claimed |= listener.test(event);
            } catch (RuntimeException failure) {
                com.dwinovo.numen.Constants.LOG.error(
                        "external task claiming listener failed for {}",
                        event.taskId(),
                        failure);
            }
        }
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
        return claimed;
    }

    /**
     * Publish a terminal lifecycle event to the dedicated-server brain queue.
     *
     * <p>Server-side embodied runtimes use this after completing any additional
     * verification represented by a claiming subscription. It is also the
     * supported bridge for background work implemented outside Numen's built-in
     * task scheduler.</p>
     */
    public static void publishExternalBrain(Event event) {
        if (event == null) return;
        ServerBrainEvents.publishTaskFinished(
                event.companionUuid(),
                event.companionName(),
                event.taskId(),
                event.taskName(),
                event.status(),
                event.message(),
                event.gameTime());
    }

    static void clearForTests() {
        LISTENERS.clear();
        CLAIMING_LISTENERS.clear();
    }
}
