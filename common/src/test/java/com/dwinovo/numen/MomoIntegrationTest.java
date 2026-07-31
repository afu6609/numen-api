package com.dwinovo.numen;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MomoIntegrationTest {

    @Test
    void managedCancellationBridgeHasExactRegistrationLifetime() {
        UUID bodyId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        MomoIntegration.Registration registration =
                MomoIntegration.installManagedTaskCanceller(
                        (body, owner, reason) -> {
                            assertEquals(bodyId, body);
                            assertEquals(ownerId, owner);
                            assertEquals("stop now", reason);
                            calls.incrementAndGet();
                            return 3;
                        });
        try {
            assertEquals(
                    3,
                    MomoIntegration.cancelManagedTasks(
                            bodyId, ownerId, "stop now"));
            assertEquals(1, calls.get());
            assertThrows(
                    IllegalStateException.class,
                    () -> MomoIntegration.installManagedTaskCanceller(
                            (body, owner, reason) -> 0));
        } finally {
            registration.close();
        }

        assertEquals(
                0,
                MomoIntegration.cancelManagedTasks(
                        bodyId, ownerId, "after close"));
    }
}
