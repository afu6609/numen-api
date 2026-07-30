package com.dwinovo.numen.api;

import com.dwinovo.numen.mcp.server.ServerBrainEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerBrainConfigurationTest {

    private static final List<ServerBrainConfiguration.CatalogEntry> CATALOG =
            List.of(new ServerBrainConfiguration.CatalogEntry(
                    "gpt-5.3-codex-spark",
                    List.of("low", "medium", "high")));

    @BeforeEach
    @AfterEach
    void reset() {
        ServerBrainConfiguration.clearForTests();
        while (!ServerBrainEvents.poll(64).isEmpty()) {
            // Drain the global event transport shared by these focused tests.
        }
    }

    @Test
    void publishesBodyIndependentAtomicSetRequest() {
        long before = System.currentTimeMillis();
        UUID player = UUID.randomUUID();

        String requestId = ServerBrainConfiguration.request(
                ServerBrainConfiguration.Action.SET,
                "gpt-5.3-codex-spark",
                "HIGH",
                ServerBrainConfiguration.Requester.player(player, "Alex"),
                42L);

        ServerBrainEvents.Event event = ServerBrainEvents.poll(1).get(0);
        assertEquals("brain_config_request", event.type());
        assertNull(event.companionUuid());
        assertEquals(42L, event.gameTime());
        assertEquals(requestId, event.data().get("requestId"));
        assertEquals("set", event.data().get("action"));
        assertEquals("gpt-5.3-codex-spark", event.data().get("model"));
        assertEquals("high", event.data().get("reasoning"));
        assertTrue(
                ((Number) event.data().get("expiresAtEpochMillis")).longValue()
                        >= before
                                + ServerBrainConfiguration.REQUEST_TTL_MILLIS);
        assertEquals(
                player.toString(),
                ((Map<?, ?>) event.data().get("requester")).get("uuid"));
        assertEquals(1, ServerBrainConfiguration.pendingCount());
    }

    @Test
    void matchingAppliedAckUpdatesStateAndTargetsOriginalRequester() {
        UUID player = UUID.randomUUID();
        String requestId = ServerBrainConfiguration.request(
                ServerBrainConfiguration.Action.SET,
                "gpt-5.3-codex-spark",
                "high",
                ServerBrainConfiguration.Requester.player(player, "Alex"),
                1L);
        AtomicReference<ServerBrainConfiguration.Report> observed =
                new AtomicReference<>();
        ServerBrainConfiguration.addReportListener(observed::set);

        ServerBrainConfiguration.Report report =
                ServerBrainConfiguration.acceptReport(
                        requestId,
                        true,
                        true,
                        null,
                        "gpt-5.3-codex-spark",
                        "high",
                        "2",
                        CATALOG);

        assertTrue(report.success());
        assertTrue(report.applied());
        assertEquals(player, report.requester().uuid());
        assertEquals(report, observed.get());
        assertEquals(0, ServerBrainConfiguration.pendingCount());
        assertEquals(
                "gpt-5.3-codex-spark",
                ServerBrainConfiguration.snapshot().model());
    }

    @Test
    void mismatchedSetAckNeverClaimsRequestedPairWasApplied() {
        String requestId = ServerBrainConfiguration.request(
                ServerBrainConfiguration.Action.SET,
                "gpt-5.3-codex-spark",
                "high",
                ServerBrainConfiguration.Requester.console("Server"),
                1L);

        ServerBrainConfiguration.Report report =
                ServerBrainConfiguration.acceptReport(
                        requestId,
                        true,
                        true,
                        null,
                        "gpt-5.6-luna",
                        "high",
                        "3",
                        CATALOG);

        assertFalse(report.success());
        assertTrue(report.error().contains("different active"));
        // The cache still reflects the sidecar's authoritative active state.
        assertEquals(
                "gpt-5.6-luna",
                ServerBrainConfiguration.snapshot().model());
    }

    @Test
    void startupReportRefreshesCatalogWithoutInventingRequester() {
        ServerBrainConfiguration.Report report =
                ServerBrainConfiguration.acceptReport(
                        null,
                        true,
                        true,
                        null,
                        "gpt-5.3-codex-spark",
                        "medium",
                        "1",
                        CATALOG);

        assertTrue(report.success());
        assertNull(report.requestId());
        assertNull(report.requester());
        assertNotNull(ServerBrainConfiguration.snapshot());
        assertEquals(CATALOG, ServerBrainConfiguration.snapshot().catalog());
    }

    @Test
    void setRequiresCompleteAtomicPair() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerBrainConfiguration.request(
                        ServerBrainConfiguration.Action.SET,
                        "gpt-5.3-codex-spark",
                        null,
                        ServerBrainConfiguration.Requester.console("Server"),
                        1L));
        assertTrue(ServerBrainEvents.poll(1).isEmpty());
    }

    @Test
    void staleRevisionCannotOverwriteNewerAuthoritativeState() {
        ServerBrainConfiguration.acceptReport(
                null,
                true,
                true,
                null,
                "gpt-5.6-luna",
                "high",
                "5",
                CATALOG);
        String requestId = ServerBrainConfiguration.request(
                ServerBrainConfiguration.Action.SET,
                "gpt-5.3-codex-spark",
                "high",
                ServerBrainConfiguration.Requester.console("Server"),
                1L);

        ServerBrainConfiguration.Report report =
                ServerBrainConfiguration.acceptReport(
                        requestId,
                        true,
                        true,
                        null,
                        "gpt-5.3-codex-spark",
                        "high",
                        "4",
                        CATALOG);

        assertFalse(report.success());
        assertTrue(report.error().contains("stale revision"));
        assertEquals("gpt-5.6-luna", report.snapshot().model());
        assertEquals("5", ServerBrainConfiguration.snapshot().revision());
        assertEquals(0, ServerBrainConfiguration.pendingCount());
    }

    @Test
    void rejectsNonNumericRevision() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ServerBrainConfiguration.acceptReport(
                        null,
                        true,
                        true,
                        null,
                        "gpt-5.3-codex-spark",
                        "high",
                        "boot-1",
                        CATALOG));
        assertNull(ServerBrainConfiguration.snapshot());
    }

    @Test
    void sameRevisionCannotNameTwoDifferentActivePairs() {
        ServerBrainConfiguration.acceptReport(
                null,
                true,
                true,
                null,
                "gpt-5.6-luna",
                "high",
                "8",
                CATALOG);

        ServerBrainConfiguration.Report report =
                ServerBrainConfiguration.acceptReport(
                        null,
                        true,
                        true,
                        null,
                        "gpt-5.3-codex-spark",
                        "high",
                        "8",
                        CATALOG);

        assertFalse(report.success());
        assertTrue(report.error().contains("reused revision"));
        assertEquals(
                "gpt-5.6-luna",
                ServerBrainConfiguration.snapshot().model());
    }

    @Test
    void appliedAckMayArriveAfterApplyDeadlineDuringAckGrace() {
        AtomicLong now = new AtomicLong(1_000L);
        ServerBrainConfiguration.setClockForTests(now::get);
        String requestId = ServerBrainConfiguration.request(
                ServerBrainConfiguration.Action.SET,
                "gpt-5.3-codex-spark",
                "HIGH",
                ServerBrainConfiguration.Requester.console("Server"),
                1L);
        now.set(32_000L); // Past the 31s apply deadline, inside the 61s ACK deadline.

        ServerBrainConfiguration.Report report =
                ServerBrainConfiguration.acceptReport(
                        requestId,
                        true,
                        true,
                        null,
                        "gpt-5.3-codex-spark",
                        "high",
                        "1",
                        CATALOG);

        assertTrue(report.success());
        assertEquals(0, ServerBrainConfiguration.pendingCount());
    }

    @Test
    void unacknowledgedRequestEventuallyExpiresAndNotifiesRequester() {
        AtomicLong now = new AtomicLong(1_000L);
        ServerBrainConfiguration.setClockForTests(now::get);
        AtomicReference<ServerBrainConfiguration.Report> observed =
                new AtomicReference<>();
        ServerBrainConfiguration.addReportListener(observed::set);
        ServerBrainConfiguration.request(
                ServerBrainConfiguration.Action.GET,
                null,
                null,
                ServerBrainConfiguration.Requester.console("Server"),
                1L);
        now.set(62_000L);

        assertEquals(1, ServerBrainConfiguration.expireRequests());
        assertEquals(0, ServerBrainConfiguration.pendingCount());
        assertFalse(observed.get().success());
        assertTrue(observed.get().error().contains("expired"));
    }
}
