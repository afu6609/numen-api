package com.dwinovo.numen.task.navigation;

import com.dwinovo.numen.task.control.BodyControlClass;
import com.dwinovo.numen.task.control.BodyControlPolicy;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.BlockPosition;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.ControlBinding;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.FollowRequest;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.MoveBlockRequest;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.Operation;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.Snapshot;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.StartDisposition;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.StartResult;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.State;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyNavigationPortsTest {

    @Test
    void unavailablePortIsTheOnlyLegacyFallbackSignal() {
        StartResult result = BodyNavigationPorts.startMoveBlock(
                new MoveBlockRequest(
                        binding("t1"),
                        new BlockPosition(10, 64, -3)));

        assertEquals(StartDisposition.UNSUPPORTED, result.disposition());
        assertTrue(result.permitsLegacyFallback());
        assertFalse(result.accepted());
    }

    @Test
    void closingOlderRegistrationDoesNotRemoveReplacement() {
        BodyNavigationPort first = rejectingPort("first");
        BodyNavigationPort second = rejectingPort("second");
        FollowRequest request =
                new FollowRequest(binding("t2"), UUID.randomUUID(), 3.0);

        BodyNavigationPorts.Registration firstRegistration =
                BodyNavigationPorts.install(first);
        BodyNavigationPorts.Registration secondRegistration =
                BodyNavigationPorts.install(second);
        try {
            firstRegistration.close();
            StartResult result = BodyNavigationPorts.startFollow(request);
            assertEquals(StartDisposition.REJECTED, result.disposition());
            assertEquals("second", result.message());
            assertFalse(result.permitsLegacyFallback());
        } finally {
            secondRegistration.close();
            firstRegistration.close();
        }
    }

    @Test
    void acceptedOperationKeepsExactBindingAndCancelsProviderOnce() {
        ControlBinding binding = binding("t3");
        AtomicInteger cancellations = new AtomicInteger();
        Operation raw = new Operation() {
            @Override
            public ControlBinding binding() {
                return binding;
            }

            @Override
            public Snapshot snapshot() {
                return Snapshot.running(State.MOVING, "walking", 4);
            }

            @Override
            public void cancel(String reason) {
                cancellations.incrementAndGet();
            }
        };
        BodyNavigationPort port = new BodyNavigationPort() {
            @Override
            public StartResult startFollow(FollowRequest request) {
                return StartResult.accepted(raw, "accepted");
            }
        };

        try (BodyNavigationPorts.Registration ignored =
                     BodyNavigationPorts.install(port)) {
            StartResult result = BodyNavigationPorts.startFollow(
                    new FollowRequest(
                            binding,
                            UUID.randomUUID(),
                            3.0));
            assertTrue(result.accepted());
            assertFalse(result.permitsLegacyFallback());
            assertSame(binding, result.operation().binding());
            assertEquals(State.MOVING, result.operation().snapshot().state());

            result.operation().cancel("task finalized");
            result.operation().cancel("duplicate cleanup");
            result.operation().close();
        }

        assertEquals(1, cancellations.get());
    }

    @Test
    void wrongAcceptedBindingIsCancelledBeforeAdmissionIsRejected() {
        ControlBinding requested = binding("requested");
        ControlBinding wrong = binding("wrong");
        AtomicInteger cancellations = new AtomicInteger();
        Operation raw = operation(wrong, cancellations, false);
        BodyNavigationPort port = new BodyNavigationPort() {
            @Override
            public StartResult startMoveBlock(MoveBlockRequest request) {
                return StartResult.accepted(raw, "accepted incorrectly");
            }
        };

        try (BodyNavigationPorts.Registration ignored =
                     BodyNavigationPorts.install(port)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> BodyNavigationPorts.startMoveBlock(
                            new MoveBlockRequest(
                                    requested,
                                    new BlockPosition(1, 64, 1))));
        }

        assertEquals(1, cancellations.get());
    }

    @Test
    void failedCancellationCanBeRetriedUntilOneSucceeds() {
        ControlBinding binding = binding("retry-cancel");
        AtomicInteger cancellations = new AtomicInteger();
        Operation raw = operation(binding, cancellations, true);
        BodyNavigationPort port = new BodyNavigationPort() {
            @Override
            public StartResult startFollow(FollowRequest request) {
                return StartResult.accepted(raw, "accepted");
            }
        };

        try (BodyNavigationPorts.Registration ignored =
                     BodyNavigationPorts.install(port)) {
            Operation guarded = BodyNavigationPorts.startFollow(
                    new FollowRequest(
                            binding,
                            UUID.randomUUID(),
                            3.0)).operation();

            assertThrows(
                    IllegalStateException.class,
                    () -> guarded.cancel("first cleanup"));
            guarded.cancel("retry cleanup");
            guarded.cancel("duplicate after success");
        }

        assertEquals(2, cancellations.get());
    }

    @Test
    void laterTickPreservesTheExactControlIdentity() {
        ControlBinding acquired = binding("t4");
        ControlBinding later =
                acquired.atTick(acquired.request().serverTick() + 20);

        assertTrue(acquired.sameIdentity(later));
        assertEquals(
                acquired.request().serverTick() + 20,
                later.request().serverTick());
    }

    private static BodyNavigationPort rejectingPort(String reason) {
        return new BodyNavigationPort() {
            @Override
            public StartResult startFollow(FollowRequest request) {
                return StartResult.rejected(reason);
            }
        };
    }

    private static Operation operation(
            ControlBinding binding,
            AtomicInteger cancellations,
            boolean failFirstCancellation) {
        return new Operation() {
            @Override
            public ControlBinding binding() {
                return binding;
            }

            @Override
            public Snapshot snapshot() {
                return Snapshot.running(State.MOVING, "walking", 0);
            }

            @Override
            public void cancel(String reason) {
                int attempt = cancellations.incrementAndGet();
                if (failFirstCancellation && attempt == 1) {
                    throw new IllegalStateException("transient cancel failure");
                }
            }
        };
    }

    private static ControlBinding binding(String sessionId) {
        return new ControlBinding(new BodyControlPolicy.Request(
                UUID.randomUUID(),
                "Momo",
                "numen-chain:llm",
                sessionId,
                BodyControlClass.DIRECTED_ACTION,
                BodyControlClass.DIRECTED_ACTION.defaultPriority(),
                100L));
    }
}
