package com.dwinovo.numen.task.navigation;

import com.dwinovo.numen.task.control.BodyControlPolicy;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Optional provider boundary for navigation executed by an external body
 * runtime.
 *
 * <p>The port is a motor sub-operation of an already selected Numen control
 * session. A provider must bind an accepted operation to the exact
 * {@link BodyControlPolicy.Request} supplied by the caller; it must not acquire
 * a second actor/session lease for the same body.
 *
 * <p>Backend selection is sticky for one task:
 * <ul>
 *   <li>{@link StartDisposition#UNSUPPORTED} is the only result that permits
 *       the caller to start its legacy navigator.</li>
 *   <li>{@link StartDisposition#ACCEPTED} commits that task to this provider
 *       until the operation reaches a terminal state or is cancelled.</li>
 *   <li>{@link StartDisposition#REJECTED} is a real start failure. Falling back
 *       after it could leave two navigators believing they own the body.</li>
 * </ul>
 */
public interface BodyNavigationPort {

    /** Outcome of provider admission, before any asynchronous navigation runs. */
    enum StartDisposition {
        /** The provider owns the operation; the legacy backend must not start. */
        ACCEPTED,
        /** No provider capability was committed; legacy fallback is safe. */
        UNSUPPORTED,
        /** The provider understood the request but could not safely start it. */
        REJECTED
    }

    /** Provider-neutral lifecycle state returned by an accepted operation. */
    enum State {
        PLANNING,
        MOVING,
        /** A live follow operation is inside its requested radius. */
        HOLDING,
        ARRIVED,
        FAILED,
        CANCELLED;

        public boolean terminal() {
            return this == ARRIVED || this == FAILED || this == CANCELLED;
        }
    }

    /** Structured failure category for task-layer recovery decisions. */
    enum FailureKind {
        NONE,
        NO_PATH,
        BOXED_IN,
        TARGET_LOST,
        OUT_OF_RANGE,
        CONTROL_LOST,
        NO_MATERIAL,
        HAZARD,
        CAPABILITY_MISMATCH,
        INTERNAL
    }

    /**
     * Exact scheduler identity under which the provider may emit body input.
     *
     * <p>Providers retain the identity fields and derive later checks only with
     * {@link #atTick(long)}. Rebuilding a request from a mutable task chain could
     * accidentally bind an old operation to a successor task.
     */
    record ControlBinding(BodyControlPolicy.Request request) {

        public ControlBinding {
            request = Objects.requireNonNull(request, "request");
            Objects.requireNonNull(request.bodyId(), "request.bodyId");
            requireText(request.bodyName(), "request.bodyName");
            requireText(request.actorId(), "request.actorId");
            requireText(request.sessionId(), "request.sessionId");
            Objects.requireNonNull(
                    request.controlClass(),
                    "request.controlClass");
        }

        public ControlBinding atTick(long serverTick) {
            return new ControlBinding(request.atTick(serverTick));
        }

        /** Compare immutable ownership identity while deliberately ignoring tick. */
        public boolean sameIdentity(ControlBinding other) {
            return other != null && sameIdentity(other.request());
        }

        /** Compare immutable ownership identity while deliberately ignoring tick. */
        public boolean sameIdentity(BodyControlPolicy.Request other) {
            return other != null
                    && request.bodyId().equals(other.bodyId())
                    && request.bodyName().equals(other.bodyName())
                    && request.actorId().equals(other.actorId())
                    && request.sessionId().equals(other.sessionId())
                    && request.controlClass() == other.controlClass()
                    && request.priority() == other.priority();
        }
    }

    /** Loader-neutral integer block coordinate. */
    record BlockPosition(int x, int y, int z) {}

    /**
     * Explicit, bounded terrain-edit authority for one navigation operation.
     *
     * <p>A positive break budget may be used for ordinary obstruction removal
     * even when downward excavation is disabled. Likewise, a positive scaffold
     * budget may be used for bridging when pillar movement is disabled.
     */
    record TerrainAllowance(
            int maxBrokenBlocks,
            int maxBreakTicks,
            int maxScaffoldBlocks,
            boolean allowPillar,
            boolean allowDigDown) {

        public TerrainAllowance {
            if (maxBrokenBlocks < 0) {
                throw new IllegalArgumentException(
                        "maxBrokenBlocks must not be negative");
            }
            if (maxBreakTicks < 0) {
                throw new IllegalArgumentException(
                        "maxBreakTicks must not be negative");
            }
            if (maxScaffoldBlocks < 0) {
                throw new IllegalArgumentException(
                        "maxScaffoldBlocks must not be negative");
            }
            if ((maxBrokenBlocks == 0) != (maxBreakTicks == 0)) {
                throw new IllegalArgumentException(
                        "block and tick break budgets must both be zero or "
                                + "both be positive");
            }
            if (allowPillar && maxScaffoldBlocks == 0) {
                throw new IllegalArgumentException(
                        "pillar movement requires a positive scaffold budget");
            }
            if (allowDigDown && maxBrokenBlocks == 0) {
                throw new IllegalArgumentException(
                        "downward excavation requires a positive break budget");
            }
        }
    }

    /**
     * Exact-cell move used by Numen's coordinate {@code goto} BLOCK form.
     *
     * <p>The requested cell is the body's destination, not a block to approach.
     * If reaching it requires terrain editing and the provider cannot guarantee
     * that semantic, it must return {@link StartDisposition#UNSUPPORTED} before
     * accepting the operation.
     */
    record MoveBlockRequest(
            ControlBinding binding,
            BlockPosition target) {

        public MoveBlockRequest {
            binding = Objects.requireNonNull(binding, "binding");
            target = Objects.requireNonNull(target, "target");
        }
    }

    /**
     * Approach one target block from any safe stance inside the supplied
     * radius.
     *
     * <p>Every position in {@code protectedPositions} is immutable for this
     * operation: a provider must not break, replace, or place a block at any of
     * those coordinates while satisfying the request.
     */
    record ApproachBlockRequest(
            ControlBinding binding,
            BlockPosition target,
            double arrivalRadius,
            TerrainAllowance terrainAllowance,
            Set<BlockPosition> protectedPositions) {

        public ApproachBlockRequest {
            binding = Objects.requireNonNull(binding, "binding");
            target = Objects.requireNonNull(target, "target");
            if (!Double.isFinite(arrivalRadius) || arrivalRadius <= 0.0) {
                throw new IllegalArgumentException(
                        "block arrival radius must be finite and positive");
            }
            terrainAllowance = Objects.requireNonNull(
                    terrainAllowance,
                    "terrainAllowance");
            Set<BlockPosition> protectedCopy = new HashSet<>(
                    Objects.requireNonNull(
                            protectedPositions,
                            "protectedPositions"));
            protectedCopy.add(target);
            protectedPositions = Set.copyOf(protectedCopy);
        }
    }

    /**
     * Approach one live dropped-item entity without assuming its occupied
     * block is a valid body destination.
     */
    record ApproachItemRequest(
            ControlBinding binding,
            UUID targetUuid,
            double arrivalRadius,
            TerrainAllowance terrainAllowance) {

        public ApproachItemRequest {
            binding = Objects.requireNonNull(binding, "binding");
            targetUuid = Objects.requireNonNull(targetUuid, "targetUuid");
            if (!Double.isFinite(arrivalRadius) || arrivalRadius <= 0.0) {
                throw new IllegalArgumentException(
                        "item arrival radius must be finite and positive");
            }
            terrainAllowance = Objects.requireNonNull(
                    terrainAllowance,
                    "terrainAllowance");
        }
    }

    /** Continuous follow of one explicit online player identity. */
    record FollowRequest(
            ControlBinding binding,
            UUID targetUuid,
            double radius) {

        public FollowRequest {
            binding = Objects.requireNonNull(binding, "binding");
            targetUuid = Objects.requireNonNull(targetUuid, "targetUuid");
            if (!Double.isFinite(radius) || radius <= 0.0) {
                throw new IllegalArgumentException(
                        "follow radius must be finite and positive");
            }
        }
    }

    /** Immutable poll result from an accepted operation. */
    record Snapshot(
            State state,
            FailureKind failure,
            String message,
            int stalledTicks) {

        public Snapshot {
            state = Objects.requireNonNull(state, "state");
            failure = Objects.requireNonNull(failure, "failure");
            message = message == null ? "" : message.trim();
            if (stalledTicks < 0) {
                throw new IllegalArgumentException(
                        "stalledTicks must not be negative");
            }
            if (state == State.FAILED && failure == FailureKind.NONE) {
                throw new IllegalArgumentException(
                        "a failed snapshot requires a failure kind");
            }
            if (state != State.FAILED && failure != FailureKind.NONE) {
                throw new IllegalArgumentException(
                        "only a failed snapshot may carry a failure kind");
            }
        }

        public static Snapshot running(
                State state,
                String message,
                int stalledTicks) {
            if (state == null || state.terminal()) {
                throw new IllegalArgumentException(
                        "running snapshot requires a non-terminal state");
            }
            return new Snapshot(
                    state,
                    FailureKind.NONE,
                    message,
                    stalledTicks);
        }

        public static Snapshot arrived(String message) {
            return new Snapshot(
                    State.ARRIVED,
                    FailureKind.NONE,
                    message,
                    0);
        }

        public static Snapshot failed(
                FailureKind failure,
                String message,
                int stalledTicks) {
            return new Snapshot(
                    State.FAILED,
                    failure,
                    message,
                    stalledTicks);
        }

        public static Snapshot cancelled(String message) {
            return new Snapshot(
                    State.CANCELLED,
                    FailureKind.NONE,
                    message,
                    0);
        }
    }

    /**
     * Monotonic terrain-edit consumption for one accepted operation.
     *
     * <p>The task layer can add this receipt to a task-wide budget before it
     * releases the operation. Providers that do not edit terrain must report
     * {@link #NONE} explicitly.</p>
     */
    record TerrainUsage(
            int brokenBlocks,
            int breakTicks,
            int scaffoldBlocks) {

        public static final TerrainUsage NONE =
                new TerrainUsage(0, 0, 0);

        public TerrainUsage {
            if (brokenBlocks < 0
                    || breakTicks < 0
                    || scaffoldBlocks < 0) {
                throw new IllegalArgumentException(
                        "terrain usage must not be negative");
            }
        }
    }

    /**
     * Opaque, asynchronously advanced provider operation.
     *
     * <p>{@link #cancel(String)} must be idempotent, including after a terminal
     * snapshot. It may release provider-local planning resources, but it must
     * synchronously detach the provider operation so it cannot emit another
     * body write after the call returns, and it must not release the enclosing
     * Numen control session. Physical handoff neutralization remains the
     * enclosing session owner's responsibility when its per-tick write fence
     * prevents the provider from writing a final stop frame. The public
     * {@link BodyNavigationPorts} facade coalesces concurrent cancellation
     * calls and stops forwarding them after one succeeds. If the provider
     * throws, a later call may retry so a transient cleanup failure cannot
     * permanently leak the operation.
     */
    interface Operation extends AutoCloseable {

        /** The exact scheduler identity captured when this operation was accepted. */
        ControlBinding binding();

        /** Poll only; the provider advances the operation from its server tick. */
        Snapshot snapshot();

        /**
         * Return the operation's cumulative, monotonic terrain-edit receipt.
         * Providers that never edit terrain must return
         * {@link TerrainUsage#NONE} explicitly.
         */
        TerrainUsage terrainUsage();

        /** Idempotently stop this operation without releasing its enclosing lease. */
        void cancel(String reason);

        @Override
        default void close() {
            cancel("navigation operation closed");
        }
    }

    /** Admission result for any request type. */
    record StartResult(
            StartDisposition disposition,
            Operation operation,
            String message) {

        public StartResult {
            disposition = Objects.requireNonNull(
                    disposition,
                    "disposition");
            message = message == null ? "" : message.trim();
            if ((disposition == StartDisposition.ACCEPTED)
                    != (operation != null)) {
                throw new IllegalArgumentException(
                        "accepted starts require exactly one operation");
            }
        }

        public static StartResult accepted(
                Operation operation,
                String message) {
            return new StartResult(
                    StartDisposition.ACCEPTED,
                    Objects.requireNonNull(operation, "operation"),
                    message);
        }

        public static StartResult unsupported(String message) {
            return new StartResult(
                    StartDisposition.UNSUPPORTED,
                    null,
                    message);
        }

        public static StartResult rejected(String message) {
            return new StartResult(
                    StartDisposition.REJECTED,
                    null,
                    message);
        }

        public boolean accepted() {
            return disposition == StartDisposition.ACCEPTED;
        }

        /**
         * The sole positive signal that a task may select its legacy backend.
         * An accepted or rejected start must never fall through to legacy.
         */
        public boolean permitsLegacyFallback() {
            return disposition == StartDisposition.UNSUPPORTED;
        }
    }

    /**
     * Start an exact-cell move. A partial provider may leave this unsupported.
     */
    default StartResult startMoveBlock(MoveBlockRequest request) {
        Objects.requireNonNull(request, "request");
        return StartResult.unsupported(
                "external exact-cell navigation is unavailable");
    }

    /**
     * Start a bounded approach to one target block.
     */
    default StartResult startApproachBlock(ApproachBlockRequest request) {
        Objects.requireNonNull(request, "request");
        return StartResult.unsupported(
                "external block-approach navigation is unavailable");
    }

    /**
     * Start a bounded approach to one dropped-item entity.
     */
    default StartResult startApproachItem(ApproachItemRequest request) {
        Objects.requireNonNull(request, "request");
        return StartResult.unsupported(
                "external dropped-item approach is unavailable");
    }

    /**
     * Start a durable follow operation for the supplied player UUID.
     */
    default StartResult startFollow(FollowRequest request) {
        Objects.requireNonNull(request, "request");
        return StartResult.unsupported(
                "external player-follow navigation is unavailable");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
