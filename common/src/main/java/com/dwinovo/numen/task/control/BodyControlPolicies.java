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
        return mayProbe(requestFor(body, chain));
    }

    public static BodyControlPolicy.Decision mayProbe(
            BodyControlPolicy.Request request) {
        return ACTIVE.get().mayProbe(Objects.requireNonNull(request, "request"));
    }

    public static BodyControlPolicy.Decision mayStart(
            NumenPlayer body,
            String actorId) {
        return mayStart(body, actorId, actorId);
    }

    public static BodyControlPolicy.Decision mayStart(
            NumenPlayer body,
            String actorId,
            String sessionId) {
        return mayStart(requestFor(
                body,
                actorId,
                sessionId,
                BodyControlClass.DIRECTED_ACTION,
                BodyControlClass.DIRECTED_ACTION.defaultPriority()));
    }

    public static BodyControlPolicy.Decision mayStart(
            BodyControlPolicy.Request request) {
        return ACTIVE.get().mayStart(Objects.requireNonNull(request, "request"));
    }

    public static BodyControlPolicy.Decision acquire(
            NumenPlayer body,
            TaskChain chain) {
        return acquire(requestFor(body, chain));
    }

    public static BodyControlPolicy.Decision acquire(
            NumenPlayer body,
            String actorId,
            String sessionId,
            BodyControlClass controlClass,
            int priority) {
        return acquire(requestFor(
                body, actorId, sessionId, controlClass, priority));
    }

    public static BodyControlPolicy.Decision acquire(
            BodyControlPolicy.Request request) {
        return ACTIVE.get().acquire(Objects.requireNonNull(request, "request"));
    }

    public static boolean owns(
            NumenPlayer body,
            TaskChain chain) {
        return owns(requestFor(body, chain));
    }

    public static boolean owns(
            NumenPlayer body,
            String actorId,
            String sessionId,
            BodyControlClass controlClass,
            int priority) {
        return owns(requestFor(
                body, actorId, sessionId, controlClass, priority));
    }

    public static boolean owns(
            BodyControlPolicy.Request request) {
        return ACTIVE.get().owns(Objects.requireNonNull(request, "request"));
    }

    public static boolean claimHandoff(
            BodyControlPolicy.Request request) {
        return ACTIVE.get().claimHandoff(
                Objects.requireNonNull(request, "request"));
    }

    public static void release(
            UUID bodyId,
            String actorId) {
        ACTIVE.get().release(bodyId, actorId);
    }

    public static void release(
            NumenPlayer body,
            TaskChain chain) {
        release(requestFor(body, chain));
    }

    public static void release(
            NumenPlayer body,
            String actorId,
            String sessionId,
            BodyControlClass controlClass,
            int priority) {
        release(requestFor(
                body, actorId, sessionId, controlClass, priority));
    }

    public static void release(
            BodyControlPolicy.Request request) {
        ACTIVE.get().release(Objects.requireNonNull(request, "request"));
    }

    public static void clear(UUID bodyId) {
        ACTIVE.get().clear(bodyId);
    }

    /**
     * Snapshot the chain's control identity once. Callers that retain a lease
     * must keep this request and use {@link BodyControlPolicy.Request#atTick(long)}
     * for later ownership checks; rebuilding it from a mutable chain can target
     * a different task session.
     */
    public static BodyControlPolicy.Request requestFor(
            NumenPlayer body,
            TaskChain chain) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(chain, "chain");
        return new BodyControlPolicy.Request(
                body.getUUID(),
                body.getGameProfile().getName(),
                chain.controlActorId(),
                chain.controlSessionId(),
                chain.controlClass(),
                chain.controlPriority(),
                body.level().getGameTime());
    }

    public static BodyControlPolicy.Request requestFor(
            NumenPlayer body,
            String actorId,
            String sessionId,
            BodyControlClass controlClass,
            int priority) {
        Objects.requireNonNull(body, "body");
        return new BodyControlPolicy.Request(
                body.getUUID(),
                body.getGameProfile().getName(),
                actorId,
                sessionId,
                controlClass,
                priority,
                body.level().getGameTime());
    }
}
