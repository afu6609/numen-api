package com.dwinovo.numen.task.navigation;

import com.dwinovo.numen.task.navigation.BodyNavigationPort.ControlBinding;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.FollowRequest;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.MoveBlockRequest;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.Operation;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.StartDisposition;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.StartResult;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide, replace-safe registry for the optional body-navigation port.
 *
 * <p>An accepted operation remains bound to the provider that created it even
 * if a newer provider is installed. Closing an older registration cannot
 * remove that replacement.
 */
public final class BodyNavigationPorts {

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private static final BodyNavigationPort UNAVAILABLE =
            new BodyNavigationPort() {};
    private static final AtomicReference<BodyNavigationPort> ACTIVE =
            new AtomicReference<>(UNAVAILABLE);

    private BodyNavigationPorts() {}

    public static Registration install(BodyNavigationPort port) {
        Objects.requireNonNull(port, "port");
        ACTIVE.set(port);
        return () -> ACTIVE.compareAndSet(port, UNAVAILABLE);
    }

    public static StartResult startMoveBlock(MoveBlockRequest request) {
        Objects.requireNonNull(request, "request");
        return guard(
                request.binding(),
                ACTIVE.get().startMoveBlock(request));
    }

    public static StartResult startFollow(FollowRequest request) {
        Objects.requireNonNull(request, "request");
        return guard(
                request.binding(),
                ACTIVE.get().startFollow(request));
    }

    private static StartResult guard(
            ControlBinding requestedBinding,
            StartResult result) {
        result = Objects.requireNonNull(
                result,
                "navigation provider returned no start result");
        if (result.disposition() != StartDisposition.ACCEPTED) {
            return result;
        }

        Operation delegate = Objects.requireNonNull(
                result.operation(),
                "accepted navigation operation");
        try {
            ControlBinding actualBinding = Objects.requireNonNull(
                    delegate.binding(),
                    "accepted operation binding");
            if (!requestedBinding.equals(actualBinding)) {
                throw new IllegalStateException(
                        "navigation provider accepted an operation under a "
                                + "different control request");
            }
        } catch (RuntimeException validationFailure) {
            cancelRejectedAdmission(delegate, validationFailure);
            throw validationFailure;
        }
        return StartResult.accepted(
                new GuardedOperation(requestedBinding, delegate),
                result.message());
    }

    /**
     * An ACCEPTED provider may already have scheduled work. If its binding
     * cannot be validated, stop that work before rejecting the admission so the
     * caller never loses the only handle to a live operation.
     */
    private static void cancelRejectedAdmission(
            Operation delegate,
            RuntimeException validationFailure) {
        try {
            delegate.cancel(
                    "navigation admission rejected: invalid control binding");
        } catch (RuntimeException cancellationFailure) {
            validationFailure.addSuppressed(cancellationFailure);
        }
    }

    /**
     * Binds the public handle to the admitted session and makes cancellation
     * idempotent even if cleanup reaches it through more than one path.
     */
    private static final class GuardedOperation implements Operation {
        private final ControlBinding binding;
        private final Operation delegate;
        private boolean cancelled;

        private GuardedOperation(
                ControlBinding binding,
                Operation delegate) {
            this.binding = binding;
            this.delegate = delegate;
        }

        @Override
        public ControlBinding binding() {
            return binding;
        }

        @Override
        public BodyNavigationPort.Snapshot snapshot() {
            return Objects.requireNonNull(
                    delegate.snapshot(),
                    "navigation provider returned no snapshot");
        }

        @Override
        public synchronized void cancel(String reason) {
            if (cancelled) return;
            String normalized = reason == null || reason.isBlank()
                    ? "navigation operation cancelled"
                    : reason.trim();
            // Mark success only after the delegate returns. If it throws, the
            // provider contract is idempotent and the next cleanup path may
            // retry; synchronization also prevents a concurrent caller from
            // mistaking an in-flight failed attempt for completed cleanup.
            delegate.cancel(normalized);
            cancelled = true;
        }
    }
}
