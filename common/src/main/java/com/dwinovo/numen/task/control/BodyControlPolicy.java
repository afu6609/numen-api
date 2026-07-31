package com.dwinovo.numen.task.control;

import java.util.UUID;

/** Optional policy installed by an external whole-body control plane. */
public interface BodyControlPolicy {

    record Request(
            UUID bodyId,
            String bodyName,
            String actorId,
            String sessionId,
            BodyControlClass controlClass,
            int priority,
            long serverTick) {

        /**
         * Source-compatible constructor for policies compiled against the
         * actor-only lease model. Such callers get one stable session per actor.
         */
        public Request(
                UUID bodyId,
                String bodyName,
                String actorId,
                BodyControlClass controlClass,
                int priority,
                long serverTick) {
            this(
                    bodyId,
                    bodyName,
                    actorId,
                    actorId,
                    controlClass,
                    priority,
                    serverTick);
        }

        /**
         * Copy this exact control identity for a policy check on a later server
         * tick. Actor, session, class, and priority are deliberately immutable.
         */
        public Request atTick(long serverTick) {
            return new Request(
                    bodyId,
                    bodyName,
                    actorId,
                    sessionId,
                    controlClass,
                    priority,
                    serverTick);
        }
    }

    record Decision(boolean granted, String reason) {
        public static Decision allow() {
            return new Decision(true, "allowed");
        }

        public static Decision deny(String reason) {
            return new Decision(
                    false,
                    reason == null || reason.isBlank()
                            ? "body control was denied"
                            : reason);
        }
    }

    /** Cheap pre-filter called before a chain performs its priority probe. */
    default Decision mayProbe(Request request) {
        return Decision.allow();
    }

    /** Admission check for a newly submitted physical task; it does not acquire. */
    default Decision mayStart(Request request) {
        return mayProbe(request);
    }

    /** Acquire or renew exclusive physical control for the selected chain. */
    default Decision acquire(Request request) {
        return Decision.allow();
    }

    /** Whether this actor currently owns the body and may perform handoff writes. */
    default boolean owns(Request request) {
        return true;
    }

    /**
     * Renew and claim this exact session for an interrupt/neutralize handoff.
     *
     * <p>A session-aware policy uses this to make cleanup the sole physical
     * writer for the current tick. A successor may acquire immediately, but
     * must defer its first write until the next tick.
     */
    default boolean claimHandoff(Request request) {
        return owns(request);
    }

    /** Release a lease previously acquired for {@code actorId}. */
    default void release(UUID bodyId, String actorId) {}

    /**
     * Release the exact actor session represented by {@code request}.
     *
     * <p>The default preserves compatibility with actor-only policies; a
     * session-aware policy overrides this method.
     */
    default void release(Request request) {
        release(request.bodyId(), request.actorId());
    }

    /** Drop every policy-side lease and cache associated with a removed body. */
    default void clear(UUID bodyId) {}
}
