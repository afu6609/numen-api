package com.dwinovo.numen.mcp.server;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpServerCommandDeduplicatorTest {

    @Test
    void successfulRequestIdRunsOnlyOnce() throws Exception {
        McpServer.CommandDeduplicator deduplicator =
                new McpServer.CommandDeduplicator(4);
        AtomicInteger runs = new AtomicInteger();
        UUID companion = UUID.randomUUID();

        McpServer.CommandDeduplicator.Outcome first = deduplicator.execute(
                "session:event-1",
                companion,
                "/time set day",
                () -> {
                    runs.incrementAndGet();
                    return "/time set day";
                });
        McpServer.CommandDeduplicator.Outcome replay = deduplicator.execute(
                "session:event-1",
                companion,
                "/time set day",
                () -> {
                    runs.incrementAndGet();
                    return "must not run";
                });

        assertEquals(1, runs.get());
        assertFalse(first.duplicate());
        assertTrue(replay.duplicate());
        assertEquals("/time set day", replay.result());
    }

    @Test
    void uncertainOutcomeIsNeverReplayed() {
        McpServer.CommandDeduplicator deduplicator =
                new McpServer.CommandDeduplicator(4);
        AtomicInteger runs = new AtomicInteger();
        UUID companion = UUID.randomUUID();

        assertThrows(
                IllegalStateException.class,
                () -> deduplicator.execute(
                        "session:event-2",
                        companion,
                        "/weather clear",
                        () -> {
                            runs.incrementAndGet();
                            throw new IllegalStateException("response lost");
                        }));
        IllegalStateException replay = assertThrows(
                IllegalStateException.class,
                () -> deduplicator.execute(
                        "session:event-2",
                        companion,
                        "/weather clear",
                        () -> {
                            runs.incrementAndGet();
                            return "must not run";
                        }));

        assertEquals(1, runs.get());
        assertTrue(replay.getMessage().contains("refusing to replay"));
    }

    @Test
    void requestIdCannotBeRebound() throws Exception {
        McpServer.CommandDeduplicator deduplicator =
                new McpServer.CommandDeduplicator(4);
        UUID companion = UUID.randomUUID();
        deduplicator.execute(
                "session:event-3",
                companion,
                "/difficulty hard",
                () -> "/difficulty hard");

        assertThrows(
                IllegalArgumentException.class,
                () -> deduplicator.execute(
                        "session:event-3",
                        companion,
                        "/difficulty peaceful",
                        () -> "must not run"));
    }
}
