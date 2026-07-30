package com.dwinovo.numen.task.control;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskChain;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide compatibility bridge to one authoritative body-control policy.
 *
 * <p>Registration is server-scoped and replace-safe: closing an older
 * registration cannot remove a newer policy.
 */
public final class BodyControlPolicies {

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private static final BodyControlPolicy ALLOW_ALL = new BodyControlPolicy() {};
    private static final AtomicReference<BodyControlPolicy> ACTIVE =
            new AtomicReference<>(ALLOW_ALL);

    private BodyControlPolicies() {}

    public static Registration install(BodyControlPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        ACTIVE.set(policy);
        return () -> ACTIVE.compareAndSet(policy, ALLOW_ALL);
    }

    public static BodyControlPolicy.Decision mayProbe(
            NumenPlayer body,
            TaskChain chain) {
        if (body == null) return BodyControlPolicy.Decision.allow();
        return ACTIVE.get().mayProbe(request(body, chain));
    }

    public static BodyControlPolicy.Decision mayStart(
            NumenPlayer body,
            String actorId) {
        return ACTIVE.get().mayStart(new BodyControlPolicy.Request(
                body.getUUID(),
                body.getGameProfile().getName(),
                actorId,
                BodyControlClass.DIRECTED_ACTION,
                BodyControlClass.DIRECTED_ACTION.defaultPriority(),
                body.level().getGameTime()));
    }

    public static BodyControlPolicy.Decision acquire(
            NumenPlayer body,
            TaskChain chain) {
        return ACTIVE.get().acquire(request(body, chain));
    }

    public static boolean owns(
            NumenPlayer body,
            TaskChain chain) {
        return ACTIVE.get().owns(request(body, chain));
    }

    public static void release(
            UUID bodyId,
            String actorId) {
        ACTIVE.get().release(bodyId, actorId);
    }

    public static void clear(UUID bodyId) {
        ACTIVE.get().clear(bodyId);
    }

    private static BodyControlPolicy.Request request(
            NumenPlayer body,
            TaskChain chain) {
        return new BodyControlPolicy.Request(
                body.getUUID(),
                body.getGameProfile().getName(),
                chain.controlActorId(),
                chain.controlClass(),
                chain.controlPriority(),
                body.level().getGameTime());
    }
}
