package com.dwinovo.numen.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ServerToolRepliesTest {

    private static final UUID COMPANION =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @AfterEach
    void tearDown() {
        ServerToolReplies.clearForTests();
    }

    @Test
    void deliversExactlyOnceAndRemovesRoute() {
        AtomicReference<String> received = new AtomicReference<>();
        ServerToolReplies.register("mcp-server-1", COMPANION, received::set);

        assertTrue(ServerToolReplies.deliver("mcp-server-1", "{\"success\":true}"));
        assertEquals("{\"success\":true}", received.get());
        assertEquals(0, ServerToolReplies.waitingCount());
        assertFalse(ServerToolReplies.deliver("mcp-server-1", "duplicate"));
    }

    @Test
    void rejectsDuplicateIds() {
        ServerToolReplies.register("mcp-server-2", COMPANION, ignored -> {});

        assertThrows(IllegalStateException.class,
                () -> ServerToolReplies.register(
                        "mcp-server-2", COMPANION, ignored -> {}));
    }

    @Test
    void removeMakesLateCompletionFallBackToNetworkRoute() {
        ServerToolReplies.register("mcp-server-3", COMPANION, ignored -> {});
        ServerToolReplies.remove("mcp-server-3");

        assertFalse(ServerToolReplies.deliver("mcp-server-3", "{}"));
        assertEquals(0, ServerToolReplies.waitingCount());
    }

    @Test
    void deathFailsOnlyCallsForThatCompanion() {
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();
        ServerToolReplies.register("mcp-server-4", COMPANION, first::set);
        ServerToolReplies.register("mcp-server-5", other, second::set);

        assertEquals(1, ServerToolReplies.failForCompanion(
                COMPANION, "{\"success\":false,\"message\":\"died\"}"));

        assertEquals("{\"success\":false,\"message\":\"died\"}", first.get());
        assertNull(second.get());
        assertEquals(1, ServerToolReplies.waitingCount());
        assertTrue(ServerToolReplies.deliver("mcp-server-5", "still alive"));
    }
}
