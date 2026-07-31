package com.dwinovo.numen;

import java.util.Objects;
import java.util.UUID;

/**
 * Process-local integration latch for deployments where {@code momo_embodied}
 * owns the fake-player lifecycle and body task bus.
 *
 * <p>The Forge engine entry point enables this before common/gameplay
 * initialization. Keeping the latch in common code lets the gameplay pack and
 * protocol handlers make the same decision without depending on Forge classes.
 * Standalone/Fabric behavior remains the historical default ({@code false}).</p>
 */
public final class MomoIntegration {

    private static volatile boolean managedBodyMode;
    private static volatile ManagedTaskCanceller managedTaskCanceller;

    @FunctionalInterface
    public interface ManagedTaskCanceller {
        int cancel(UUID bodyId, UUID requesterId, String reason);
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private MomoIntegration() {}

    public static void enableManagedBodyMode() {
        managedBodyMode = true;
    }

    public static boolean managedBodyMode() {
        return managedBodyMode;
    }

    /**
     * Install the one managed-body cancellation bridge for this server process.
     * The returned registration only removes the exact callback it installed.
     */
    public static synchronized Registration installManagedTaskCanceller(
            ManagedTaskCanceller canceller) {
        Objects.requireNonNull(canceller, "canceller");
        if (managedTaskCanceller != null) {
            throw new IllegalStateException(
                    "a managed task canceller is already installed");
        }
        managedTaskCanceller = canceller;
        return () -> clearManagedTaskCanceller(canceller);
    }

    /** Route an owner-authorized client Stop request into Momo's task bus. */
    public static int cancelManagedTasks(
            UUID bodyId,
            UUID requesterId,
            String reason) {
        ManagedTaskCanceller canceller = managedTaskCanceller;
        return canceller == null
                ? 0
                : canceller.cancel(
                        Objects.requireNonNull(bodyId, "bodyId"),
                        Objects.requireNonNull(requesterId, "requesterId"),
                        reason == null || reason.isBlank()
                                ? "cancelled by owner"
                                : reason);
    }

    private static synchronized void clearManagedTaskCanceller(
            ManagedTaskCanceller installed) {
        if (managedTaskCanceller == installed) {
            managedTaskCanceller = null;
        }
    }
}
