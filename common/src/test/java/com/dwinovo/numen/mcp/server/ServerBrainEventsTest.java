package com.dwinovo.numen.mcp.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerBrainEventsTest {

    @AfterEach
    void clearQueue() {
        ServerBrainEvents.clearForTests();
    }

    @Test
    void drainsChatInFifoOrder() {
        UUID player = UUID.randomUUID();
        ServerBrainEvents.publishChat(player, "Steve", "first", 10L);
        ServerBrainEvents.publishChat(player, "Steve", "second", 11L);

        List<ServerBrainEvents.Event> first = ServerBrainEvents.poll(1);
        List<ServerBrainEvents.Event> second = ServerBrainEvents.poll(16);

        assertEquals(List.of("first"), first.stream().map(ServerBrainEvents.Event::message).toList());
        assertEquals(List.of("second"), second.stream().map(ServerBrainEvents.Event::message).toList());
        assertTrue(ServerBrainEvents.poll(16).isEmpty());
    }

    @Test
    void dropsOldestChatWhenQueueIsFull() {
        UUID player = UUID.randomUUID();
        for (int i = 0; i <= ServerBrainEvents.MAX_QUEUED_EVENTS; i++) {
            ServerBrainEvents.publishChat(player, "Alex", "message-" + i, i);
        }

        List<ServerBrainEvents.Event> drained = ServerBrainEvents.poll(64);

        assertEquals(64, drained.size());
        assertEquals("message-1", drained.get(0).message());
    }

    @Test
    void trimsAndClampsPollRequests() {
        UUID player = UUID.randomUUID();
        ServerBrainEvents.publishChat(player, " Alex ", " hello ", 1L);

        ServerBrainEvents.Event event = ServerBrainEvents.poll(0).get(0);

        assertEquals("Alex", event.playerName());
        assertEquals("hello", event.message());
        assertEquals("player_chat", event.type());
    }
}
