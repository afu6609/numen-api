package com.dwinovo.numen.task.navigation;

import com.dwinovo.numen.task.control.BodyControlClass;
import com.dwinovo.numen.task.control.BodyControlPolicy;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.ApproachBlockRequest;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.ApproachItemRequest;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.BlockPosition;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.ControlBinding;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.FollowRequest;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.MoveBlockRequest;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.Operation;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.Snapshot;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.StartDisposition;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.StartResult;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.State;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.TerrainAllowance;
import com.dwinovo.numen.task.navigation.BodyNavigationPort.TerrainUsage;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
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
    void itemApproachIsUnsupportedByDefault() {
        StartResult result = BodyNavigationPorts.startApproachItem(
                new ApproachItemRequest(
                        binding("item-default"),
                        UUID.randomUUID(),
                        1.25,
                        noTerrainEdits()));

        assertEquals(StartDisposition.UNSUPPORTED, result.disposition());
        assertTrue(result.permitsLegacyFallback());
    }

    @Test
    void blockApproachIsUnsupportedByDefault() {
        StartResult result = BodyNavigationPorts.startApproachBlock(
                new ApproachBlockRequest(
                        binding("block-default"),
                        new BlockPosition(10, 63, -3),
                        3.5,
                        noTerrainEdits(),
                        Set.of(new BlockPosition(9, 63, -3))));

        assertEquals(StartDisposition.UNSUPPORTED, result.disposition());
        assertTrue(result.permitsLegacyFallback());
    }

    @Test
    void blockApproachAlwaysProtectsTargetAndCopiesCallerSet() {
        BlockPosition target = new BlockPosition(10, 63, -3);
        BlockPosition adjacent = new BlockPosition(9, 63, -3);
        BlockPosition later = new BlockPosition(8, 63, -3);
        Set<BlockPosition> supplied = new HashSet<>();
        supplied.add(adjacent);

        ApproachBlockRequest request = new ApproachBlockRequest(
                binding("block-protected-target"),
                target,
                3.5,
                noTerrainEdits(),
                supplied);
        supplied.add(later);

        assertEquals(
                Set.of(target, adjacent),
                request.protectedPositions());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.protectedPositions().add(later));
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
            public TerrainUsage terrainUsage() {
                return new TerrainUsage(2, 37, 1);
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
            assertEquals(
                    new TerrainUsage(2, 37, 1),
                    result.operation().terrainUsage());

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
    void wrongItemApproachBindingIsCancelledBeforeAdmissionIsRejected() {
        ControlBinding requested = binding("item-requested");
        ControlBinding wrong = binding("item-wrong");
        AtomicInteger cancellations = new AtomicInteger();
        Operation raw = operation(wrong, cancellations, false);
        BodyNavigationPort port = new BodyNavigationPort() {
            @Override
            public StartResult startApproachItem(
                    ApproachItemRequest request) {
                return StartResult.accepted(raw, "accepted incorrectly");
            }
        };

        try (BodyNavigationPorts.Registration ignored =
                     BodyNavigationPorts.install(port)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> BodyNavigationPorts.startApproachItem(
                            new ApproachItemRequest(
                                    requested,
                                    UUID.randomUUID(),
                                    1.25,
                                    noTerrainEdits())));
        }

        assertEquals(1, cancellations.get());
    }

    @Test
    void wrongBlockApproachBindingIsCancelledBeforeAdmissionIsRejected() {
        ControlBinding requested = binding("block-requested");
        ControlBinding wrong = binding("block-wrong");
        AtomicInteger cancellations = new AtomicInteger();
        Operation raw = operation(wrong, cancellations, false);
        BodyNavigationPort port = new BodyNavigationPort() {
            @Override
            public StartResult startApproachBlock(
                    ApproachBlockRequest request) {
                return StartResult.accepted(raw, "accepted incorrectly");
            }
        };

        try (BodyNavigationPorts.Registration ignored =
                     BodyNavigationPorts.install(port)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> BodyNavigationPorts.startApproachBlock(
                            new ApproachBlockRequest(
                                    requested,
                                    new BlockPosition(1, 12, 1),
                                    3.5,
                                    noTerrainEdits(),
                                    Set.of())));
        }

        assertEquals(1, cancellations.get());
    }

    @Test
    void terrainAllowanceRejectsInconsistentBudgets() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainAllowance(-1, 0, 0, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainAllowance(0, -1, 0, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainAllowance(0, 0, -1, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainAllowance(1, 0, 0, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainAllowance(0, 20, 0, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainAllowance(0, 0, 0, true, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> new TerrainAllowance(0, 0, 0, false, true));
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
            public TerrainUsage terrainUsage() {
                return TerrainUsage.NONE;
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

    private static TerrainAllowance noTerrainEdits() {
        return new TerrainAllowance(0, 0, 0, false, false);
    }
}
