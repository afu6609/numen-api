package com.dwinovo.numen.task.control;

import java.util.UUID;

/** Optional policy installed by an external whole-body control plane. */
public interface BodyControlPolicy {

    record Request(
            UUID bodyId,
            String bodyName,
            String actorId,
            BodyControlClass controlClass,
            int priority,
            long serverTick) {}

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

    /** Release a lease previously acquired for {@code actorId}. */
    default void release(UUID bodyId, String actorId) {}

    /** Drop every policy-side lease and cache associated with a removed body. */
    default void clear(UUID bodyId) {}
}
